package com.bhawana.lms.service;

record DocumentStorageDescriptor(
        String originalFileName,
        String contentType,
        String checksum,
        String storageKey
) {
}
