/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.audit;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.LoggerBuilder;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditLogger}.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class AuditLoggerTest {

    @Mock
    Instance<OpenTelemetry> openTelemetryInstance;

    @Test
    void logMcpCallSilentWhenOtelUnavailable() {
        when(openTelemetryInstance.isResolvable()).thenReturn(false);
        AuditLogger auditLogger = new AuditLogger(openTelemetryInstance);

        AuditEvent event = new AuditEvent(
                "tools/call", "my-tool", AuditEvent.OUTCOME_SUCCESS,
                null, 120L, "svc", "1.0", "org-1",
                null, null, null, null, 512L, null
        );

        // Should silently do nothing when OTEL is unavailable — no NPE.
        auditLogger.logMcpCall(event);
    }

    @Test
    void logAuthFailureSilentWhenOtelUnavailable() {
        when(openTelemetryInstance.isResolvable()).thenReturn(false);
        AuditLogger auditLogger = new AuditLogger(openTelemetryInstance);

        AuthenticationFailureAuditEvent event = new AuthenticationFailureAuditEvent(
                AuthenticationFailureAuditEvent.REASON_INVALID_API_KEY,
                "svc-1", "svc", "1.0", "org-1",
                "10.0.0.1", 401, null
        );

        auditLogger.logAuthFailure(event);
    }

    @Test
    void logMcpCallWithOtelEmitsRecord() {
        OpenTelemetry otel = mock(OpenTelemetry.class);
        LoggerProvider bridge = mock(LoggerProvider.class);
        LoggerBuilder loggerBuilder = mock(LoggerBuilder.class);
        Logger otelLogger = mock(Logger.class);
        LogRecordBuilder recordBuilder = mock(LogRecordBuilder.class);

        when(openTelemetryInstance.isResolvable()).thenReturn(true);
        when(openTelemetryInstance.get()).thenReturn(otel);
        when(otel.getLogsBridge()).thenReturn(bridge);
        when(bridge.loggerBuilder(anyString())).thenReturn(loggerBuilder);
        when(loggerBuilder.build()).thenReturn(otelLogger);
        when(otelLogger.logRecordBuilder()).thenReturn(recordBuilder);
        when(recordBuilder.setTimestamp(any())).thenReturn(recordBuilder);
        when(recordBuilder.setBody(anyString())).thenReturn(recordBuilder);
        when(recordBuilder.setAllAttributes(any())).thenReturn(recordBuilder);
        when(recordBuilder.setSeverity(any())).thenReturn(recordBuilder);
        when(recordBuilder.setSeverityText(anyString())).thenReturn(recordBuilder);

        AuditLogger auditLogger = new AuditLogger(openTelemetryInstance);

        AuditEvent event = new AuditEvent(
                "tools/call", "my-tool", AuditEvent.OUTCOME_SUCCESS,
                null, 120L, "svc", "1.0", "org-1",
                "req-1", "sess-1", "1.2.3.4", "user-1", 512L, "trace-1"
        );

        auditLogger.logMcpCall(event);

        verify(recordBuilder).emit();
    }
}
