package com.example.chatseedance2.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTaskStatusTest {

    @Test
    void onlyProviderTerminalStatesAreTerminalLocally() {
        assertThat(VideoTaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(VideoTaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(VideoTaskStatus.SUBMISSION_UNKNOWN.isRecoverable()).isTrue();
        assertThat(VideoTaskStatus.POLLING_DELAYED.isRecoverable()).isTrue();
        assertThat(VideoTaskStatus.PROCESSING.isRecoverable()).isTrue();
    }
}
