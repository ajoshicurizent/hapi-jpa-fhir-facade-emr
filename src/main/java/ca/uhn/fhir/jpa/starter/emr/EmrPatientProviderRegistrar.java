package ca.uhn.fhir.jpa.starter.emr;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;

@Configuration
public class EmrPatientProviderRegistrar {

	private static final Logger log = LoggerFactory.getLogger(EmrPatientProviderRegistrar.class);

	@Bean
	ApplicationRunner replaceJpaPatientProvider(RestfulServer restfulServer, EmrPatientResourceProvider emrPatient) {
		return args -> {
			List<IResourceProvider> toRemove = new ArrayList<>();
			for (IResourceProvider provider : restfulServer.getResourceProviders()) {
				if (Patient.class.equals(provider.getResourceType())) {
					toRemove.add(provider);
				}
			}
			for (IResourceProvider provider : toRemove) {
				restfulServer.unregisterProvider(provider);
			}
			restfulServer.registerProvider(emrPatient);
			log.info("Patient FHIR operations now read and write EMR database rows, not HAPI hfj_* tables");
		};
	}
}
