/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.processinstance;

import static io.camunda.zeebe.protocol.record.Assertions.assertThat;

import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceModificationIntent;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;

public class ModifyProcessInstanceRejectionTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();
  private static final String PROCESS_ID = "process";

  @Rule public final TestWatcher watcher = new RecordingExporterTestWatcher();

  @Test
  public void shouldRejectCommandWhenProcessInstanceIsUnknown() {
    // given
    final long unknownKey = 12345L;

    // when
    ENGINE
        .processInstance()
        .withInstanceKey(unknownKey)
        .modification()
        .activateElement("A")
        .expectRejection()
        .modify();

    // then
    final var rejectionRecord =
        RecordingExporter.processInstanceModificationRecords().onlyCommandRejections().getFirst();

    assertThat(rejectionRecord)
        .hasIntent(ProcessInstanceModificationIntent.MODIFY)
        .hasRejectionType(RejectionType.NOT_FOUND)
        .hasRejectionReason(
            String.format(
                "Expected to modify process instance but no process instance found with key '%d'",
                unknownKey))
        .hasKey(unknownKey);
  }

  @Test
  public void shouldRejectCommandWhenAtLeastOneElementIdIsUnknown() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(PROCESS_ID).startEvent().userTask("A").endEvent().done())
        .deploy();
    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();
    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("A")
        .await();

    // when
    final var rejection =
        ENGINE
            .processInstance()
            .withInstanceKey(processInstanceKey)
            .modification()
            .activateElement("A")
            .activateElement("B")
            .activateElement("C")
            .expectRejection()
            .modify();

    // then
    assertThat(rejection)
        .describedAs("Expect that elements with ids 'B' and 'C' are not found")
        .hasRejectionType(RejectionType.INVALID_ARGUMENT)
        .hasRejectionReason(
            ("Expected to modify instance of process '%s' but it contains one or more activate instructions"
                    + " with an element that could not be found: 'B', 'C'")
                .formatted(PROCESS_ID));
  }

  @Test
  public void shouldRejectCommandWhenAtLeastOneElementIsInsideMultiInstance() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(PROCESS_ID)
                .startEvent()
                .userTask("A")
                .subProcess(
                    "subprocess",
                    s -> s.embeddedSubProcess().startEvent().manualTask("B").manualTask("C").done())
                .multiInstance(m -> m.zeebeInputCollectionExpression("[1,2,3]"))
                .manualTask("D")
                .endEvent()
                .done())
        .deploy();
    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();
    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("A")
        .await();

    // when
    final var rejection =
        ENGINE
            .processInstance()
            .withInstanceKey(processInstanceKey)
            .modification()
            .activateElement("B")
            .activateElement("C")
            .activateElement("D")
            .expectRejection()
            .modify();

    // then
    assertThat(rejection)
        .describedAs("Expect that elements with ids 'B' and 'C' are inside multi-instance")
        .hasRejectionType(RejectionType.INVALID_ARGUMENT)
        .hasRejectionReason(
            ("Expected to modify instance of process '%s' but it contains one or more activate"
                    + " instructions for elements inside a multi-instance subprocess: 'B', 'C'."
                    + " The activation of elements inside a multi-instance subprocess is not supported.")
                .formatted(PROCESS_ID));
  }
}