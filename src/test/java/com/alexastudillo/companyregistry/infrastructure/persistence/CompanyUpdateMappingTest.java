package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class CompanyUpdateMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject AtomicOperation atomic;
    @Inject UuidV7Generator ids;
    private CompanyRecord original() {
        var now=Instant.parse("2026-10-17T12:00:00.123456Z");
        return new CompanyRecord(new Company(ids.next(),UUID.randomUUID(),UUID.randomUUID(),CompanyStatus.ACTIVE,
                AggregateVersion.initial()),"CODE","COMPANY","DESCRIPTION","https://host/LogoAb.png",new Audit(now,"Creator",now,"Creator"));
    }
    private CompanyRecord changed(CompanyRecord row,UUID tenant,UUID id) {
        return new CompanyRecord(new Company(id,tenant,row.company().partyId(),row.company().status(),new AggregateVersion(1)),
                "NEW","NEW NAME",null,null,new Audit(row.audit().createdAt(),row.audit().createdBy(),row.audit().updatedAt().plusSeconds(1),"Updater-aB"));
    }
    @Test @RunOnVertxContext void protectedReadAndSingleConditionedWritePreserveIdentityAuditStatusAndSite(UniAsserter a) {
        var row=original(); var tenant=row.company().tenantId();var id=row.company().id();var next=changed(row,tenant,id);
        var site=new SiteRecord(new Site(ids.next(),tenant,id,SiteType.BRANCH,SiteStatus.DRAFT,AggregateVersion.initial()),"SITE","SITE",row.audit());
        a.execute(()->atomic.execute(()->companies.insert(row).chain(()->sites.insert(site))));
        a.assertNull(()->atomic.execute(()->companies.findForUpdate(UUID.randomUUID(),id)));
        a.assertNull(()->atomic.execute(()->companies.findForUpdate(tenant,ids.next())));
        a.assertEquals(()->atomic.execute(()->companies.findForUpdate(tenant,id).chain(locked->{assertEquals(row,locked);return companies.update(next,0);})),next);
        a.assertEquals(()->atomic.read(()->companies.find(tenant,id)),next);
        a.assertEquals(()->atomic.read(()->sites.find(tenant,id,site.site().id())),site);
        a.assertFailedWith(()->atomic.execute(()->companies.update(next,0)),f->assertEquals(PersistenceFailure.Kind.STALE_VERSION,((PersistenceFailure)f).kind()));
        a.assertFailedWith(()->atomic.execute(()->companies.update(changed(row,UUID.randomUUID(),id),0)),f->assertEquals(PersistenceFailure.Kind.STALE_VERSION,((PersistenceFailure)f).kind()));
        a.assertEquals(()->atomic.read(()->companies.find(tenant,id)),next);
    }
}
