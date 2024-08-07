package org.prebid.server.auction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iab.openrtb.request.BidRequest;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.VertxTest;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.currency.proto.CurrencyConversionRates;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.metric.Metrics;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestCurrency;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.spring.config.model.ExternalConversionProperties;
import org.prebid.server.vertx.httpclient.HttpClient;
import org.prebid.server.vertx.httpclient.model.HttpClientResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class CurrencyConversionServiceTest extends VertxTest {

    private static final String USD = "USD";
    private static final String GBP = "GBP";
    private static final String EUR = "EUR";
    private static final String UAH = "UAH";
    private static final String AUD = "AUD";
    private static final String URL = "http://currency-rates/latest.json";

    @Mock
    private HttpClient httpClient;
    @Mock
    private Vertx vertx;
    @Mock
    private Metrics metrics;
    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    private CurrencyConversionService currencyService;

    @BeforeEach
    public void setUp() throws JsonProcessingException {
        final Map<String, Map<String, BigDecimal>> currencyRates = new HashMap<>();
        currencyRates.put(GBP, singletonMap(EUR, BigDecimal.valueOf(1.3)));
        currencyRates.put(UAH, singletonMap(EUR, BigDecimal.valueOf(0.2)));
        givenHttpClientReturnsResponse(httpClient, 200,
                mapper.writeValueAsString(CurrencyConversionRates.of(null, currencyRates)));

        currencyService = createInitializedService(URL, 1L, -3600L, httpClient);
    }

    @Test
    public void creationShouldFailOnInvalidCurrencyServerUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createInitializedService("invalid-url", 1L, -1L, httpClient))
                .withMessage("URL supplied is not valid: invalid-url");
    }

    @Test
    public void initializeShouldSetLastUpdatedDate() {
        assertThat(currencyService.getLastUpdated()).isNotNull();
    }

    @Test
    public void currencyRatesGaugeShouldReportStale() {
        // then
        final ArgumentCaptor<BooleanSupplier> gaugeValueProviderCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(metrics).createCurrencyRatesGauge(gaugeValueProviderCaptor.capture());
        final BooleanSupplier gaugeValueProvider = gaugeValueProviderCaptor.getValue();

        assertThat(gaugeValueProvider.getAsBoolean()).isTrue();
    }

    @Test
    public void currencyRatesGaugeShouldReportNotStale() {
        // when
        metrics = mock(Metrics.class); // original mock is already spoiled by service initialization in setUp
        currencyService = createInitializedService(URL, 1L, 3600L, httpClient);

        // then
        final ArgumentCaptor<BooleanSupplier> gaugeValueProviderCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(metrics).createCurrencyRatesGauge(gaugeValueProviderCaptor.capture());
        final BooleanSupplier gaugeValueProvider = gaugeValueProviderCaptor.getValue();

        assertThat(gaugeValueProvider.getAsBoolean()).isFalse();
    }

    @Test
    public void convertCurrencyShouldReturnSamePriceIfBidAndServerCurrenciesEquals() {
        // given
        final BigDecimal price = BigDecimal.valueOf(100);

        // when
        final BigDecimal convertedPrice = currencyService.convertCurrency(price,
                givenBidRequestWithCurrencies(null, false), USD, USD);

        // then
        assertThat(convertedPrice).isSameAs(price);
    }

    @Test
    public void convertCurrencyShouldUseUSDByDefaultIfBidCurrencyIsNull() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates =
                singletonMap(EUR, singletonMap(USD, BigDecimal.valueOf(1.2)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, null);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(1.200));
    }

    @Test
    public void convertCurrencyShouldReturnConvertedByStraightMultiplierPrice() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = singletonMap(GBP,
                singletonMap(EUR, BigDecimal.valueOf(1.1565)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), GBP, EUR);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(1.156));
    }

    @Test
    public void convertCurrencyShouldReturnConvertedByInvertedMultiplierPrice() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = singletonMap(GBP, singletonMap(EUR,
                BigDecimal.valueOf(1.1565)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, GBP);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.865));
    }

    @Test
    public void convertCurrencyShouldReturnConvertedByIntermediateMultiplierPrice() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = new HashMap<>();
        requestConversionRates.put(GBP, singletonMap(USD, BigDecimal.valueOf(1.4306)));
        requestConversionRates.put(EUR, singletonMap(USD, BigDecimal.valueOf(1.2304)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, GBP);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.860));
    }

    @Test
    public void convertCurrencyShouldReturnConvertedBySingleDigitMultiplierPrice() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = new HashMap<>();
        requestConversionRates.put(EUR, singletonMap(USD, BigDecimal.valueOf(0.5)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.valueOf(1.23),
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, USD);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.615));
    }

    @Test
    public void convertCurrencyShouldUseLatestRatesIfRequestRatesIsNull() {
        // when
        final BigDecimal price = currencyService.convertCurrency(
                BigDecimal.ONE, givenBidRequestWithCurrencies(null, false), EUR, GBP);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.769));
    }

    @Test
    public void convertCurrencyShouldUseConversionRateFromServerIfusepbsratesIsTrue() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = new HashMap<>();
        requestConversionRates.put(EUR, singletonMap(USD, BigDecimal.valueOf(0.87)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, true), EUR, GBP);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.769));
    }

    @Test
    public void convertCurrencyShouldUseConversionRateFromRequestIfusepbsratesIsFalse() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = singletonMap(EUR, singletonMap(USD,
                BigDecimal.valueOf(0.6)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, USD);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.600));
    }

    @Test
    public void convertCurrencyShouldUseLatestRatesIfMultiplierWasNotFoundInRequestRates() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = singletonMap(USD,
                singletonMap(EUR, BigDecimal.valueOf(0.9)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, UAH);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(5));
    }

    @Test
    public void convertCurrencyShouldUseCrossRateIfOtherRatesAreNotAvailable() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = new HashMap<>();
        requestConversionRates.put(USD, Map.of(GBP, BigDecimal.valueOf(2),
                EUR, BigDecimal.valueOf(0.5)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), GBP, EUR);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(0.25));
    }

    @Test
    public void convertCurrencyShouldUseCrossRateIfOtherRatesAreNotAvailableReversed() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = new HashMap<>();
        requestConversionRates.put(USD, Map.of(GBP, BigDecimal.valueOf(2),
                EUR, BigDecimal.valueOf(0.5)));

        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(requestConversionRates, false), EUR, GBP);

        // then
        assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(4));
    }

    @Test
    public void convertCurrencyShouldReturnSamePriceIfBidCurrencyIsNullAndServerCurrencyUSD() {
        // when
        final BigDecimal price = currencyService.convertCurrency(BigDecimal.ONE,
                givenBidRequestWithCurrencies(emptyMap(), false), USD, null);

        // then
        assertThat(price.compareTo(BigDecimal.ONE)).isZero();
    }

    @Test
    public void convertCurrencyShouldFailWhenRequestRatesIsNullAndNoExternalRatesProvided() {
        // when
        final CurrencyConversionService currencyConversionService = new CurrencyConversionService(null);

        // when and then
        assertThatExceptionOfType(PreBidException.class)
                .isThrownBy(() -> currencyConversionService.convertCurrency(BigDecimal.ONE,
                        givenBidRequestWithCurrencies(null, false), EUR, GBP))
                .withMessage("Unable to convert from currency EUR to desired ad server currency GBP");
    }

    @Test
    public void convertCurrencyShouldThrowPrebidExceptionIfServerAndRequestRatesAreNull() {
        // when and then
        assertThatExceptionOfType(PreBidException.class)
                .isThrownBy(() -> currencyService.convertCurrency(BigDecimal.ONE,
                        givenBidRequestWithCurrencies(null, false), USD, EUR))
                .withMessage("Unable to convert from currency USD to desired ad server currency EUR");
    }

    @Test
    public void convertCurrencyShouldThrowPrebidExceptionIfMultiplierWasNotFoundFromBothRates() {
        // given
        final Map<String, Map<String, BigDecimal>> requestConversionRates = singletonMap(USD,
                singletonMap(EUR, BigDecimal.valueOf(0.8434)));

        givenHttpClientReturnsResponse(httpClient, 503, "server unavailable");

        // when
        currencyService = createInitializedService(URL, 1L, -1L, httpClient);

        // then
        assertThatExceptionOfType(PreBidException.class)
                .isThrownBy(() -> currencyService.convertCurrency(BigDecimal.ONE,
                        givenBidRequestWithCurrencies(requestConversionRates, false), EUR, AUD))
                .withMessage("Unable to convert from currency EUR to desired ad server currency AUD");
    }

    @Test
    public void convertCurrencyShouldThrowExceptionWhenCurrencyServerResponseStatusNot200() {
        // given
        givenHttpClientReturnsResponse(httpClient, 503, "server unavailable");

        // when
        currencyService = createInitializedService(URL, 1L, -1L, httpClient);

        // then
        assertThatExceptionOfType(PreBidException.class)
                .isThrownBy(() -> currencyService.convertCurrency(BigDecimal.ONE,
                        givenBidRequestWithCurrencies(null, false), UAH, AUD))
                .withMessage("Unable to convert from currency UAH to desired ad server currency AUD");
    }

    @Test
    public void convertCurrencyShouldThrowExceptionWhenCurrencyServerResponseContainsMalformedBody() {
        // given
        givenHttpClientReturnsResponse(httpClient, 200, "{\"foo\": \"bar\"}");

        // when
        currencyService = createInitializedService(URL, 1L, -1L, httpClient);

        // then
        assertThatExceptionOfType(PreBidException.class)
                .isThrownBy(() -> currencyService.convertCurrency(BigDecimal.ONE,
                        givenBidRequestWithCurrencies(null, false), UAH, AUD))
                .withMessage("Unable to convert from currency UAH to desired ad server currency AUD");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void initializeShouldMakeOneInitialRequestAndTwoScheduled() {
        // given
        given(vertx.setPeriodic(anyLong(), any())).willReturn(1L);
        final HttpClient httpClient = mock(HttpClient.class);
        givenHttpClientReturnsResponse(httpClient, 200, "{\"foo\": \"bar\"}");

        // when and then
        currencyService = createInitializedService(URL, 1000, -1L, httpClient);

        final ArgumentCaptor<Handler<Long>> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(vertx).setPeriodic(eq(1000L), handlerCaptor.capture());
        // fire timer two times
        final Handler<Long> handler = handlerCaptor.getValue();
        handler.handle(1L);
        handler.handle(1L);

        verify(httpClient, times(3)).get(anyString(), anyLong());
    }

    private CurrencyConversionService createInitializedService(String url,
                                                               long refreshPeriod,
                                                               long staleAfter,
                                                               HttpClient httpClient) {

        final CurrencyConversionService currencyService = new CurrencyConversionService(
                new ExternalConversionProperties(
                        url,
                        1000L,
                        refreshPeriod,
                        staleAfter,
                        null,
                        vertx,
                        httpClient,
                        metrics,
                        clock,
                        jacksonMapper));

        currencyService.initialize(Promise.promise());

        return currencyService;
    }

    private static void givenHttpClientReturnsResponse(HttpClient httpClient, int statusCode, String response) {
        final HttpClientResponse httpClientResponse = HttpClientResponse.of(statusCode, null, response);
        given(httpClient.get(anyString(), anyLong()))
                .willReturn(Future.succeededFuture(httpClientResponse));
    }

    private BidRequest givenBidRequestWithCurrencies(Map<String, Map<String, BigDecimal>> requestCurrencies,
                                                     Boolean usepbsrates) {
        return BidRequest.builder()
                .ext(ExtRequest.of(ExtRequestPrebid.builder()
                        .currency(ExtRequestCurrency.of(requestCurrencies, usepbsrates)).build()))
                .build();
    }
}
