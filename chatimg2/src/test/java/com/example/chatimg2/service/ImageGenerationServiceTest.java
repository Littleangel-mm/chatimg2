package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.exception.BusinessException;
import com.example.chatimg2.repository.GenerationRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationServiceTest {

    @Mock
    private KeyService keyService;
    @Mock
    private GenerationRecordRepository recordRepository;
    @Mock
    private GenerationRecordService recordService;
    @Mock
    private AsyncGenerationExecutor asyncGenerationExecutor;
    @Mock
    private ImageResultDeliveryService imageResultDeliveryService;
    @Mock
    private GenerationRecordViewService recordViewService;
    @Mock
    private ImageStorageConfig storageConfig;
    @Mock
    private AuthService authService;

    @InjectMocks
    private ImageGenerationService imageGenerationService;

    @Test
    void returnsRemoteDisplayUrlOnlyToRecordOwner() {
        UserAccount owner = user(7);
        GenerationRecord record = completedRemoteRecord(12, 7, "https://cdn.example/result.png?token=secret");
        when(recordRepository.findById(12)).thenReturn(Optional.of(record));
        when(recordViewService.prepare(record)).thenReturn(record);

        String result = imageGenerationService.getRemoteDisplayUrlForUser(owner, 12);

        assertThat(result).isEqualTo("https://cdn.example/result.png?token=secret");
    }

    @Test
    void rejectsRemoteDisplayUrlForAnotherUser() {
        GenerationRecord record = completedRemoteRecord(12, 7, "https://cdn.example/result.png");
        when(recordRepository.findById(12)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> imageGenerationService.getRemoteDisplayUrlForUser(user(8), 12))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看此记录");
    }

    @Test
    void rejectsInvalidRemoteDisplayUrl() {
        UserAccount owner = user(7);
        GenerationRecord record = completedRemoteRecord(12, 7, "not-a-url");
        when(recordRepository.findById(12)).thenReturn(Optional.of(record));
        when(recordViewService.prepare(record)).thenReturn(record);

        assertThatThrownBy(() -> imageGenerationService.getRemoteDisplayUrlForUser(owner, 12))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("远程图片结果暂不可用");
    }

    private UserAccount user(int id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        return user;
    }

    private GenerationRecord completedRemoteRecord(int id, int userId, String remoteUrl) {
        GenerationRecord record = new GenerationRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setStatus(GenerationState.COMPLETED);
        record.setRemoteImageUrl(remoteUrl);
        return record;
    }
}
