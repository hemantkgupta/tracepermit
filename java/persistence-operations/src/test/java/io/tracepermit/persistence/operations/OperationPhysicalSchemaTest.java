package io.tracepermit.persistence.operations;

import static org.assertj.core.api.Assertions.*;

import io.tracepermit.application.BusinessOperationStorageException;
import org.junit.jupiter.api.Test;

final class OperationPhysicalSchemaTest {
  private static final String PREFIX = "CREATE TABLE `tp_operation_authority_controls` (\n";

  @Test
  void declarationChecksRetainExactExpressionsAndIgnoreQuotedIdentifierDecoys() {
    String ddl = PREFIX + "  `lock_marker` smallint NOT NULL DEFAULT 0 CHECK (`lock_marker` = 0),\n"
        + "  `control_state` varchar(16) NOT NULL,\n"
        + "  CONSTRAINT `CHECK (fake)` CHECK (`control_state` in ('staged','ready','quarantined'))\n"
        + ") ENGINE=InnoDB";
    assertThat(OperationPhysicalSchema.mariaDeclaredChecks("tp_operation_authority_controls", ddl))
        .containsExactlyInAnyOrder("`lock_marker` = 0", "`control_state` in ('staged','ready','quarantined')");
  }

  @Test
  void missingChecksStayMissingAndCannotBeInventedByAQuotedIdentifier() {
    assertThat(OperationPhysicalSchema.mariaDeclaredChecks("tp_operation_authority_controls",
        PREFIX + "  `CHECK (fake)` int NOT NULL\n) ENGINE=InnoDB")).isEmpty();
  }

  @Test
  void unsupportedAmbiguousMalformedAndOversizedDeclarationsRefuseReadinessWithoutDiagnostics() {
    for (String ddl : java.util.List.of(
        "CREATE TABLE `other` (\n)",
        PREFIX + "`lock_marker` int CHECK (`lock_marker` = 0), CHECK (`lock_marker` = 0)\n)",
        PREFIX + "`x` int COMMENT 'CHECK (fake)'\n)",
        PREFIX + "`x` int /* CHECK (fake) */\n)",
        PREFIX + "`x` int /*!100000 CHECK (fake) */\n)",
        PREFIX + "`x` int -- CHECK (fake)\n)",
        PREFIX + "`x` int # CHECK (fake)\n)",
        PREFIX + "`x` int CHECK ((CHECK (fake)))\n)",
        PREFIX + "`x` int CHECK (1\n)",
        PREFIX + "`unterminated int\n)",
        PREFIX + "`x` int COMMENT 'escaped\\' CHECK (fake)'\n)",
        PREFIX + "`x` int COMMENT 'doubled''CHECK (fake)'\n)",
        PREFIX + "\"x\" int\n)",
        PREFIX + ") CHECK (fake)",
        PREFIX + "x".repeat(65537))) {
      assertThatThrownBy(() -> OperationPhysicalSchema.mariaDeclaredChecks("tp_operation_authority_controls", ddl))
          .isInstanceOf(BusinessOperationStorageException.class).hasMessage("OPERATION_SCHEMA_NOT_READY")
          .hasNoCause();
    }
  }
}
