package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface LoanDocumentStorageService {

    StoredDocument store(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            MultipartFile file
    );

    byte[] retrieve(String storageKey);

    List<StorageEntry> listAll(String prefix);

    record StorageEntry(String key, byte[] content) {
    }
}
