package ca.uhn.fhir.jpa.starter.emr;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Observation;
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
	ApplicationRunner replaceJpaPatientProvider(
			RestfulServer restfulServer,
			EmrPatientResourceProvider emrPatient,
			EmrObservationResourceProvider emrObservation) {
		return args -> {
			unregisterJpa(restfulServer, Patient.class);
			unregisterJpa(restfulServer, Observation.class);
			restfulServer.registerProvider(emrPatient);
			restfulServer.registerProvider(emrObservation);
			log.info("Patient and Observation FHIR operations now read EMR database rows, not HAPI hfj_* tables");
		};
	}

	private static void unregisterJpa(RestfulServer restfulServer, Class<?> resourceType) {
		List<IResourceProvider> toRemove = new ArrayList<>();
		for (IResourceProvider provider : restfulServer.getResourceProviders()) {
			if (resourceType.equals(provider.getResourceType())) {
				toRemove.add(provider);
			}
		}
		for (IResourceProvider provider : toRemove) {
			restfulServer.unregisterProvider(provider);
		}
	}
}
