package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import java.util.List;

public interface ReportRequestRepositoryCustom {

    List<ReportRequest> claimBatchForProcessing(List<ReportRequestStatus> statuses, int batchSize);
}
