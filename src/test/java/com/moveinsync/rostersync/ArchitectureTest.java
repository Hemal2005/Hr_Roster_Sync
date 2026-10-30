package com.moveinsync.rostersync;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.moveinsync.rostersync");

	@Test
	void domainDependsOnlyOnTheJdkAndPlainLibraries() {
		classes().that().resideInAPackage("..domain..")
				.should().onlyDependOnClassesThat().resideInAnyPackage(
						"java..",
						"com.moveinsync.rostersync.domain..",
						"com.google.i18n.phonenumbers..")
				.because("core rules must be testable without a web server, database or broker")
				.check(PRODUCTION_CLASSES);
	}

	@Test
	void applicationDependsOnPortsNotOnAdaptersOrTheWebLayer() {
		noClasses().that().resideInAPackage("..application..")
				.should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api..")
				.because("use cases talk to the outside through ports the infrastructure implements")
				.check(PRODUCTION_CLASSES);
	}

	@Test
	void domainNeverReachesIntoInfrastructure() {
		noClasses().that().resideInAPackage("..domain..")
				.should().dependOnClassesThat().resideInAPackage("..infrastructure..")
				.because("infrastructure implements domain ports, never the other way round")
				.check(PRODUCTION_CLASSES);
	}
}
