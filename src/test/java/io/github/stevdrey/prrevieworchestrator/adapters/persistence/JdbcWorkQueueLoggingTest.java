package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcWorkQueueLoggingTest {
    private static final String SECRET = "synthetic-secret-private-provider-body";

    @ParameterizedTest
    @CsvSource({"DEBUG, false, false", "INFO, true, false", "DEBUG, true, false",
        "DEBUG, false, true", "INFO, true, true"})
    void springRollbackLogsOnlySafeMetadata(String level, boolean brokenRollback, boolean knownFailure) throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        if (brokenRollback) doThrow(new SQLException("synthetic rollback disconnect")).when(connection).rollback();
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var jdbc = mock(JdbcTemplate.class);
        var lease = new Lease(Queue.OUTBOX, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        var intent = new Intent(7, "a".repeat(40), "BASELINE", "fixture", "REQUEST_REVIEW");
        var scope = new RepositoryScope(101, 1001);
        var command = new Command(lease, scope, intent, intent.key(scope));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(lease.id()), eq(lease.token())))
                .thenReturn(List.of(lease.id()));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(lease.id()))).thenReturn(true);
        // Real Spring transaction handling, including its logging on a JDBC rollback SQLException.
        var queue = new JdbcWorkQueue(jdbc, new DataSourceTransactionManager(source),
                new Limits(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(8)));
        RuntimeException unsafe = new IllegalStateException(SECRET, new RuntimeException(SECRET));
        if (knownFailure) {
            unsafe = new Failure(ErrorCode.REMOTE_UNCERTAIN, true);
            unsafe.initCause(new RuntimeException(SECRET));
        }
        unsafe.addSuppressed(new RuntimeException(SECRET));
        RuntimeException adapterFailure = unsafe;
        Logger spring = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level original = spring.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        spring.setLevel(Level.toLevel(level));
        root.addAppender(appender);
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> queue.executeCommand(command,
                    () -> {}, () -> { throw adapterFailure; }));
            Throwable callback = failure instanceof TransactionSystemException system
                    ? system.getApplicationException() : failure;
            Failure safe = assertInstanceOf(Failure.class, callback);
            assertEquals(knownFailure ? ErrorCode.REMOTE_UNCERTAIN : ErrorCode.INTERNAL_ERROR, safe.code());
            assertTrue(safe.retryable());
            assertNull(safe.getCause());
            assertEquals(0, safe.getSuppressed().length);
            assertNotSame(adapterFailure, safe);
            assertTrue(appender.list.stream().anyMatch(event -> event.getLoggerName().equals(TransactionTemplate.class.getName())
                    && event.getThrowableProxy() != null
                    && event.getLevel().equals(brokenRollback ? Level.ERROR : Level.DEBUG)), "Exercise Spring's actual exception log path");
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(SECRET));
                if (event.getThrowableProxy() != null) {
                    assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(SECRET));
                }
            }
        } finally {
            root.detachAppender(appender);
            spring.setLevel(original);
            appender.stop();
        }
    }
}
