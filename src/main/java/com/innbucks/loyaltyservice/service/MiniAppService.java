package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.MiniApp;
import com.innbucks.loyaltyservice.repository.MiniAppRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MiniAppService {

    private final MiniAppRepository miniApps;

    public MiniAppService(MiniAppRepository miniApps) {
        this.miniApps = miniApps;
    }

    @Transactional(readOnly = true)
    public List<Dtos.MiniAppManifest> manifest(UUID tenantId, UUID merchantId) {
        return miniApps.findEnabled(tenantId, merchantId).stream()
                .map(m -> new Dtos.MiniAppManifest(m.getId(), m.getSlug(), m.getName(),
                        m.getDescription(), m.getIconUrl(), m.getEntryUrl()))
                .toList();
    }

    public MiniApp register(MiniApp app) {
        return miniApps.save(app);
    }
}
