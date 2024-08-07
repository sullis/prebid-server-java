package org.prebid.server.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Closeable;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class CloseableAdapterTest {

    @Mock
    private Closeable closeable;
    @Mock
    private Promise<Void> completionPromise;

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new CloseableAdapter(null));
    }

    @Test
    public void closeShouldInvokeHandlerWithSuccededFuture() {
        // when
        new CloseableAdapter(closeable).close(completionPromise);

        // then
        verify(completionPromise).handle(eq(Future.succeededFuture()));
    }

    @Test
    public void closeShouldInvokeHandlerWithFailedFutureIfIOExceptionThrown() throws IOException {
        // given
        final IOException exception = new IOException("message");
        willThrow(exception).given(closeable).close();

        // when
        new CloseableAdapter(closeable).close(completionPromise);

        // then
        verify(completionPromise).handle(argThat(future -> future.failed() && future.cause() == exception));
    }
}
