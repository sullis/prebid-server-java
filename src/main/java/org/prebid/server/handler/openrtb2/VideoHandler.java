package org.prebid.server.handler.openrtb2;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.prebid.server.analytics.model.VideoEvent;
import org.prebid.server.analytics.reporter.AnalyticsReporterDelegator;
import org.prebid.server.auction.ExchangeService;
import org.prebid.server.auction.VideoResponseFactory;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.CachedDebugLog;
import org.prebid.server.auction.model.WithPodErrors;
import org.prebid.server.auction.requestfactory.VideoRequestFactory;
import org.prebid.server.cache.CoreCacheService;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.exception.UnauthorizedAccountException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.log.Logger;
import org.prebid.server.log.LoggerFactory;
import org.prebid.server.metric.MetricName;
import org.prebid.server.metric.Metrics;
import org.prebid.server.model.Endpoint;
import org.prebid.server.model.HttpRequestContext;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.model.PrivacyContext;
import org.prebid.server.proto.response.VideoResponse;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountAuctionConfig;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.util.ObjectUtil;
import org.prebid.server.version.PrebidVersionProvider;
import org.prebid.server.vertx.verticles.server.HttpEndpoint;
import org.prebid.server.vertx.verticles.server.application.ApplicationResource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class VideoHandler implements ApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(VideoHandler.class);

    private static final MetricName REQUEST_TYPE_METRIC = MetricName.video;

    private final VideoRequestFactory videoRequestFactory;
    private final VideoResponseFactory videoResponseFactory;
    private final ExchangeService exchangeService;
    private final CoreCacheService coreCacheService;
    private final AnalyticsReporterDelegator analyticsDelegator;
    private final Metrics metrics;
    private final Clock clock;
    private final PrebidVersionProvider prebidVersionProvider;
    private final JacksonMapper mapper;

    public VideoHandler(VideoRequestFactory videoRequestFactory,
                        VideoResponseFactory videoResponseFactory,
                        ExchangeService exchangeService,
                        CoreCacheService coreCacheService, AnalyticsReporterDelegator analyticsDelegator,
                        Metrics metrics,
                        Clock clock,
                        PrebidVersionProvider prebidVersionProvider,
                        JacksonMapper mapper) {

        this.videoRequestFactory = Objects.requireNonNull(videoRequestFactory);
        this.videoResponseFactory = Objects.requireNonNull(videoResponseFactory);
        this.exchangeService = Objects.requireNonNull(exchangeService);
        this.coreCacheService = Objects.requireNonNull(coreCacheService);
        this.analyticsDelegator = Objects.requireNonNull(analyticsDelegator);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
        this.prebidVersionProvider = Objects.requireNonNull(prebidVersionProvider);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public List<HttpEndpoint> endpoints() {
        return Collections.singletonList(HttpEndpoint.of(HttpMethod.POST, Endpoint.openrtb2_video.value()));
    }

    @Override
    public void handle(RoutingContext routingContext) {
        // Prebid Server interprets request.tmax to be the maximum amount of time that a caller is willing to wait
        // for bids. However, tmax may be defined in the Stored Request data.
        // If so, then the trip to the backend might use a significant amount of this time. We can respect timeouts
        // more accurately if we note the real start time, and use it to compute the auction timeout.
        final long startTime = clock.millis();

        final VideoEvent.VideoEventBuilder videoEventBuilder = VideoEvent.builder()
                .httpContext(HttpRequestContext.from(routingContext));

        videoRequestFactory.fromRequest(routingContext, startTime)
                .map(contextToErrors -> addToEvent(
                        contextToErrors.getData(), videoEventBuilder::auctionContext, contextToErrors))

                .compose(contextToErrors -> exchangeService.holdAuction(contextToErrors.getData())
                        .map(context -> WithPodErrors.of(context, contextToErrors.getPodErrors())))
                // populate event with updated context
                .map(contextToErrors ->
                        addToEvent(contextToErrors.getData(), videoEventBuilder::auctionContext, contextToErrors))

                .map(result -> videoResponseFactory.toVideoResponse(
                        result.getData(), result.getData().getBidResponse(),
                        result.getPodErrors()))

                .map(videoResponse -> addToEvent(videoResponse, videoEventBuilder::bidResponse, videoResponse))
                .onComplete(responseResult -> handleResult(responseResult, videoEventBuilder, routingContext,
                        startTime));
    }

    private static <T, R> R addToEvent(T field, Consumer<T> consumer, R result) {
        consumer.accept(field);
        return result;
    }

    private void handleResult(AsyncResult<VideoResponse> responseResult,
                              VideoEvent.VideoEventBuilder videoEventBuilder,
                              RoutingContext routingContext,
                              long startTime) {

        final boolean responseSucceeded = responseResult.succeeded();
        final MetricName metricRequestStatus;
        final List<String> errorMessages;
        final HttpResponseStatus status;
        final String body;
        final VideoResponse videoResponse = responseSucceeded ? responseResult.result() : null;

        final HttpServerResponse response = routingContext.response();
        enrichResponseWithCommonHeaders(routingContext);

        if (responseSucceeded) {
            metricRequestStatus = MetricName.ok;
            errorMessages = Collections.emptyList();

            status = HttpResponseStatus.OK;
            enrichWithSuccessfulHeaders(response);
            body = mapper.encodeToString(videoResponse);
        } else {
            final Throwable exception = responseResult.cause();
            if (exception instanceof InvalidRequestException) {
                metricRequestStatus = MetricName.badinput;
                errorMessages = ((InvalidRequestException) exception).getMessages();
                logger.info("Invalid request format: {}", errorMessages);

                status = HttpResponseStatus.BAD_REQUEST;
                body = errorMessages.stream()
                        .map(msg -> "Invalid request format: " + msg)
                        .collect(Collectors.joining("\n"));
            } else if (exception instanceof UnauthorizedAccountException) {
                metricRequestStatus = MetricName.badinput;
                final String errorMessage = exception.getMessage();
                logger.info("Unauthorized: {}", errorMessage);
                errorMessages = Collections.singletonList(errorMessage);

                status = HttpResponseStatus.UNAUTHORIZED;
                body = "Unauthorised: " + errorMessage;
            } else {
                metricRequestStatus = MetricName.err;
                logger.error("Critical error while running the auction", exception);

                final String message = exception.getMessage();
                errorMessages = Collections.singletonList(message);

                status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                body = "Critical error while running the auction: " + message;
            }
        }

        VideoEvent videoEvent = videoEventBuilder.status(status.code()).errors(errorMessages).build();
        final AuctionContext auctionContext = videoEvent.getAuctionContext();

        final CachedDebugLog cachedDebugLog = auctionContext != null ? auctionContext.getCachedDebugLog() : null;
        final String cacheKey = shouldCacheLog(status.code(), cachedDebugLog)
                ? cacheDebugLog(auctionContext, videoEvent.getErrors())
                : null;
        if (status.code() != 200 && cacheKey != null) {
            videoEvent = updateEventWithDebugCacheMessage(videoEvent, cacheKey);
        }
        final PrivacyContext privacyContext = auctionContext != null ? auctionContext.getPrivacyContext() : null;
        final TcfContext tcfContext = privacyContext != null ? privacyContext.getTcfContext() : TcfContext.empty();

        respondWith(routingContext, status, body, startTime, metricRequestStatus, videoEvent, tcfContext);
    }

    private boolean shouldCacheLog(int status, CachedDebugLog cachedDebugLog) {
        return cachedDebugLog != null && cachedDebugLog.isEnabled() && (status != 200 || !cachedDebugLog.hasBids());
    }

    private String cacheDebugLog(AuctionContext auctionContext, List<String> errors) {
        final CachedDebugLog cachedDebugLog = auctionContext.getCachedDebugLog();
        cachedDebugLog.setErrors(errors);

        final AccountAuctionConfig accountAuctionConfig =
                ObjectUtil.getIfNotNull(auctionContext.getAccount(), Account::getAuction);
        final Integer videoCacheTtl =
                ObjectUtil.getIfNotNull(accountAuctionConfig, AccountAuctionConfig::getVideoCacheTtl);

        return coreCacheService.cacheVideoDebugLog(cachedDebugLog, videoCacheTtl);
    }

    private VideoEvent updateEventWithDebugCacheMessage(VideoEvent videoEvent, String cacheKey) {
        final List<String> errors = new ArrayList<>();
        errors.add("[Debug cache ID: %s]".formatted(cacheKey));
        errors.addAll(videoEvent.getErrors());
        return videoEvent.toBuilder().errors(errors).build();
    }

    private void respondWith(RoutingContext routingContext,
                             HttpResponseStatus status,
                             String body,
                             long startTime,
                             MetricName metricRequestStatus,
                             VideoEvent event,
                             TcfContext tcfContext) {

        final boolean responseSent = HttpUtil.executeSafely(routingContext, Endpoint.openrtb2_video,
                response -> response
                        .exceptionHandler(this::handleResponseException)
                        .setStatusCode(status.code())
                        .end(body));

        if (responseSent) {
            metrics.updateRequestTimeMetric(REQUEST_TYPE_METRIC, clock.millis() - startTime);
            metrics.updateRequestTypeMetric(REQUEST_TYPE_METRIC, metricRequestStatus);
            analyticsDelegator.processEvent(event, tcfContext);
        } else {
            metrics.updateRequestTypeMetric(REQUEST_TYPE_METRIC, MetricName.networkerr);
        }
    }

    private void handleResponseException(Throwable throwable) {
        logger.warn("Failed to send video response: {}", throwable.getMessage());
        metrics.updateRequestTypeMetric(REQUEST_TYPE_METRIC, MetricName.networkerr);
    }

    private void enrichResponseWithCommonHeaders(RoutingContext routingContext) {
        final MultiMap responseHeaders = routingContext.response().headers();
        HttpUtil.addHeaderIfValueIsNotEmpty(
                responseHeaders, HttpUtil.X_PREBID_HEADER, prebidVersionProvider.getNameVersionRecord());

        final MultiMap requestHeaders = routingContext.request().headers();
        if (requestHeaders.contains(HttpUtil.SEC_BROWSING_TOPICS_HEADER)) {
            responseHeaders.add(HttpUtil.OBSERVE_BROWSING_TOPICS_HEADER, "?1");
        }
    }

    private void enrichWithSuccessfulHeaders(HttpServerResponse response) {
        response.headers()
                .add(HttpUtil.CONTENT_TYPE_HEADER, HttpHeaderValues.APPLICATION_JSON);
    }
}
