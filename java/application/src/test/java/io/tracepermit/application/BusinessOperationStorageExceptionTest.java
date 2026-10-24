package io.tracepermit.application;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class BusinessOperationStorageExceptionTest {
  @Test
  void fixedDiagnosticCannotAcquireDriverDetailsAsCauseOrSuppressedFailure() {
    var failure = new BusinessOperationStorageException(BusinessOperationStorageException.Code.OPERATION_STATE_STALE);
    var driver = new SQLException("synthetic-sensitive-driver-diagnostic");
    failure.addSuppressed(driver);
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(failure.getCause()).isNull();
    assertThatThrownBy(() -> failure.initCause(driver)).isInstanceOf(IllegalStateException.class);
    assertThat(failure).hasMessage("OPERATION_STATE_STALE").hasNoCause();
    assertThat(failure.toString()).doesNotContain("synthetic-sensitive");
  }
}
