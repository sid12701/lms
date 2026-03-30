package com.bhawana.lms.service;

import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminMetadataService {

    public AdminMetadataResponse getMetadata() {
        return new AdminMetadataResponse(
                enumNames(RoleCode.values()),
                enumNames(LspStatus.values()),
                enumNames(UserStatus.values())
        );
    }

    private static <E extends Enum<E>> List<String> enumNames(E[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .toList();
    }

    public record AdminMetadataResponse(
            List<String> roleCodes,
            List<String> lspStatuses,
            List<String> userStatuses
    ) {
    }
}
