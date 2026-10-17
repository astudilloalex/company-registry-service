package com.alexastudillo.companyregistry.contract;

import java.util.UUID;

/** Public provider fixtures shared by JVM and independent process tests. */
public final class CompanyProviderFixtures {
    private CompanyProviderFixtures() {}
    private static final String NOW = "2026-10-17T14:30:00.123456Z";
    public static String detail(UUID id, String type, String state) {
        return "{\"status\":200,\"code\":\"successful\",\"data\":{\"partyId\":\""+id+"\",\"type\":\""+type+"\",\"recordStatus\":\""+state+"\",\"displayName\":\"COMPAÑÍA\",\"version\":3,\"createdAt\":\""+NOW+"\",\"updatedAt\":\""+NOW+"\",\"createdBy\":\"Original-aB\",\"updatedBy\":\"Editor-cD\",\"legalEntityDetails\":{\"legalName\":\"COMPAÑÍA S.A.S.\",\"tradeName\":\"ÁGIL\",\"legalFormCode\":\"SAS\",\"incorporationCountryCode\":\"EC\",\"incorporatedOn\":\"2020-01-15\",\"dissolvedOn\":null}}}";
    }
    public static String identifier(UUID id, UUID identifier, String status) {
        return "{\"identifierId\":\""+identifier+"\",\"partyId\":\""+id+"\",\"identifierSchemeId\":\""+UUID.randomUUID()+"\",\"schemeCode\":\"EC_RUC\",\"maskedValue\":\"*********0001\",\"status\":\""+status+"\",\"isPrimary\":true,\"issuerCode\":\"SRI\",\"issuedOn\":\"2020-01-15\",\"expiresOn\":null,\"verifiedAt\":\""+NOW+"\",\"verifiedBy\":\"Verifier-aB\",\"version\":2,\"createdAt\":\""+NOW+"\",\"updatedAt\":\""+NOW+"\"}";
    }
    public static String page(String data, int size, String next) {
        return page(data,size,next,size,size==0?0:1);
    }
    public static String page(String data,int size,String next,int total,int pages) {
        return "{\"status\":200,\"code\":\"successful\",\"data\":["+data+"],\"nextCursor\":"+(next==null?"null":"\""+next+"\"")+",\"prevCursor\":null,\"totalElements\":"+total+",\"totalPages\":"+pages+",\"numberOfElements\":"+size+"}";
    }
    public static String fullFirstPage(UUID party,UUID first,String next) {
        var items=new java.util.StringJoiner(",");
        for(int i=0;i<200;i++) items.add(identifier(party,i==0?first:UUID.randomUUID(),"VERIFIED"));
        return page(items.toString(),200,next,201,2);
    }
}
