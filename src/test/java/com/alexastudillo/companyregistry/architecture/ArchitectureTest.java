package com.alexastudillo.companyregistry.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.architecture.fixtures.application.HttpCoupledApplication;
import com.alexastudillo.companyregistry.architecture.fixtures.domain.FrameworkCoupledDomain;
import com.alexastudillo.companyregistry.architecture.fixtures.domain.OuterCoupledDomain;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final String BASE = "com.alexastudillo.companyregistry";

    private static final ArchRule PURE_DOMAIN = classes().that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "..domain..", "com.google.i18n.phonenumbers..");

    private static final ArchRule INNER_APPLICATION = classes().that().resideInAPackage("..application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "..application..", "..domain..", "io.smallrye.mutiny..");

    private static final ArchRule API_USES_PORTS = noClasses().that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage(BASE + ".infrastructure..");

    private static final ArchRule INFRASTRUCTURE_HAS_NO_API = noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..api..");

    @Test
    void productionDependenciesPointInward() {
        var production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
        PURE_DOMAIN.check(production);
        INNER_APPLICATION.check(production);
        API_USES_PORTS.check(production);
        INFRASTRUCTURE_HAS_NO_API.check(production);
    }

    @Test
    void domainRuleRejectsFrameworkCoupling() {
        var violation = new ClassFileImporter().importClasses(FrameworkCoupledDomain.class);
        assertTrue(PURE_DOMAIN.evaluate(violation).hasViolation());
    }

    @Test
    void applicationRuleRejectsHttpAndPersistenceCoupling() {
        var violation = new ClassFileImporter().importClasses(HttpCoupledApplication.class);
        assertTrue(INNER_APPLICATION.evaluate(violation).hasViolation());
    }

    @Test
    void domainRuleRejectsApplicationPersistenceTransportAndReactiveDependencies() {
        var violation = new ClassFileImporter().importClasses(OuterCoupledDomain.class);
        var report = PURE_DOMAIN.evaluate(violation).getFailureReport().toString();
        assertTrue(report.contains("application.site.SiteMutation"));
        assertTrue(report.contains("jakarta.persistence.EntityManager"));
        assertTrue(report.contains("jakarta.ws.rs.core.Response"));
        assertTrue(report.contains("io.smallrye.mutiny.Uni"));
    }
}
