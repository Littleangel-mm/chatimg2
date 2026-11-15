package com.example.chatimg2.service;

import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ActivationKeyRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeyServiceTest {

    @Mock
    private ActivationKeyRepository keyRepository;

    @Mock
    private UserAccountRepository userRepository;

    @InjectMocks
    private KeyService keyService;

    @Test
    void administratorCreditUpdateSynchronizesBoundUser() {
        UserAccount user = user(7, 200, 20);
        ActivationKey key = key(11, 100, 20, user);
        stubBinding(user, List.of(key));

        ActivationKey updated = keyService.updateKeyCredits(11, 180);

        assertEquals(180, updated.getTotalCredits());
        assertEquals(280, user.getTotalCredits());
        assertEquals(0, key.getUsedCredits());
    }

    @Test
    void userUsageConsumesFreeCreditsBeforeBoundKeys() {
        UserAccount user = user(7, 300, 130);
        ActivationKey first = key(11, 100, 0, user);
        ActivationKey second = key(12, 100, 0, user);
        stubBinding(user, List.of(first, second));

        Optional<Integer> chargedKey = keyService.syncUserUsageToBoundKeys(user);

        assertEquals(30, first.getUsedCredits());
        assertEquals(0, second.getUsedCredits());
        assertEquals(Optional.of(11), chargedKey);
    }

    @Test
    void inactiveBoundKeyDoesNotGrantCredits() {
        UserAccount user = user(7, 200, 0);
        ActivationKey key = key(11, 100, 10, user);
        key.setStatus((short) 0);
        stubBinding(user, List.of(key));

        UserAccount synced = keyService.syncUserCredits(user);

        assertEquals(100, synced.getTotalCredits());
        assertEquals(0, key.getUsedCredits());
        assertTrue(synced.getRemainingCredits() >= 0);
    }

    private void stubBinding(UserAccount user, List<ActivationKey> keys) {
        lenient().when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(keyRepository.findAllByUserEmailOrderByCreatedAtDesc(user.getEmail())).thenReturn(keys);
        when(keyRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        for (ActivationKey key : keys) {
            lenient().when(keyRepository.findByIdForUpdate(key.getId())).thenReturn(Optional.of(key));
            lenient().when(keyRepository.findById(key.getId())).thenReturn(Optional.of(key));
        }
    }

    private UserAccount user(int id, int totalCredits, int usedCredits) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setEmail("user@qq.com");
        user.setTotalCredits(totalCredits);
        user.setUsedCredits(usedCredits);
        return user;
    }

    private ActivationKey key(int id, int totalCredits, int usedCredits, UserAccount user) {
        ActivationKey key = new ActivationKey();
        key.setId(id);
        key.setKeyCode("IMG2-" + id);
        key.setTotalCredits(totalCredits);
        key.setUsedCredits(usedCredits);
        key.setStatus((short) 1);
        key.setUserId(user.getId());
        key.setUserEmail(user.getEmail());
        return key;
    }
}
