package com.alexastudillo.companyregistry.application.site;

public interface AddressFingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(AddressMutation.Command command);
    boolean matches(AddressMutation.Command command, short version, String keyId, String hash);
}
