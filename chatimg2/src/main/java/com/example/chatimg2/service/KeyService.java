package com.example.chatimg2.service;

import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.repository.ActivationKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KeyService {

    private final ActivationKeyRepository keyRepository;

    public PageResult<ActivationKey> getKeysPage(int page, int size) {
        Page<ActivationKey> result = keyRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        PageResult<ActivationKey> pageResult = new PageResult<>();
        pageResult.setList(result.getContent());
        pageResult.setTotal(result.getTotalElements());
        pageResult.setPage(page);
        pageResult.setSize(size);
        pageResult.setTotalPages(result.getTotalPages());
        return pageResult;
    }

    public Optional<ActivationKey> getByKeyCode(String keyCode) {
        if (keyCode == null || keyCode.isBlank()) {
            return Optional.empty();
        }
        return keyRepository.findByKeyCode(keyCode.trim().toUpperCase());
    }

    public ActivationKey createKey(Integer totalCredits) {
        ActivationKey key = new ActivationKey();
        key.setKeyCode(generateKeyCode());
        key.setTotalCredits(totalCredits != null ? totalCredits : 100);
        return keyRepository.save(key);
    }

    private String generateKeyCode() {
        String code;
        do {
            code = "IMG2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        } while (keyRepository.existsByKeyCode(code));
        return code;
    }

    @Transactional
    public ActivationKey updateKeyCredits(Integer id, Integer totalCredits) {
        if (totalCredits == null || totalCredits < 0) {
            throw new RuntimeException("积分必须大于等于 0");
        }
        ActivationKey key = keyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        if (totalCredits < key.getUsedCredits()) {
            throw new RuntimeException(
                    "总积分不能小于已用积分（" + key.getUsedCredits() + "），请设置为至少 "
                            + key.getUsedCredits() + "，或使用「重置已用」");
        }
        key.setTotalCredits(totalCredits);
        return keyRepository.save(key);
    }

    @Transactional
    public ActivationKey resetUsedCredits(Integer id) {
        ActivationKey key = keyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        key.setUsedCredits(0);
        return keyRepository.save(key);
    }

    @Transactional
    public void deleteKey(Integer id) {
        if (!keyRepository.existsById(id)) {
            throw new RuntimeException("密钥不存在");
        }
        keyRepository.deleteById(id);
    }

    public Optional<ActivationKey> activate(String keyCode) {
        if (keyCode == null || keyCode.isBlank()) {
            return Optional.empty();
        }
        Optional<ActivationKey> keyOpt = keyRepository.findByKeyCode(keyCode.trim().toUpperCase());
        if (keyOpt.isPresent()) {
            ActivationKey key = keyOpt.get();
            if (key.getStatus() == 0) {
                return Optional.empty();
            }
            return Optional.of(key);
        }
        return Optional.empty();
    }

    @Transactional
    public boolean deductCredits(Integer keyId, int cost) {
        ActivationKey key = keyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        if (key.getRemainingCredits() < cost) {
            return false;
        }
        key.setUsedCredits(key.getUsedCredits() + cost);
        keyRepository.save(key);
        return true;
    }

    @Transactional
    public void refundCredits(Integer keyId, int amount) {
        ActivationKey key = keyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        key.setUsedCredits(Math.max(0, key.getUsedCredits() - amount));
        keyRepository.save(key);
    }
}
