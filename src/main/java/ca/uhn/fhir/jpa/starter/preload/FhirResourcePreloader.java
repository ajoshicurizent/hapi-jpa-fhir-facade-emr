package ca.uhn.fhir.jpa.starter.preload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.UrlUtil;

/**
 * Reads FHIR JSON resources from a classpath (or filesystem) pattern and stores
 * them in the JPA repository at startup. Typical use: drop StructureDefinition,
 * ValueSet, CodeSystem, SearchParameter, etc. under {@code src/main/resources/fhir/}.
 *
 * <p>Resources are upserted via the resource DAO (update-by-id, or conditional
 * update-by-{@code url} when no id is present), so repeated startups stay idempotent.
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "hapi.fhir.preload_resources", name = "enabled", havingValue = "true")
public class FhirResourcePreloader implements ApplicationRunner {

	private static final Logger ourLog = LoggerFactory.getLogger(FhirResourcePreloader.class);

	private final AppProperties myAppProperties;
	private final FhirContext myFhirContext;
	private final DaoRegistry myDaoRegistry;

	public FhirResourcePreloader(
			AppProperties theAppProperties, FhirContext theFhirContext, DaoRegistry theDaoRegistry) {
		myAppProperties = theAppProperties;
		myFhirContext = theFhirContext;
		myDaoRegistry = theDaoRegistry;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		String pattern = myAppProperties.getPreload_resources().getPath();
		Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
		if (resources.length == 0) {
			ourLog.info("FHIR resource preload enabled, but no files matched pattern '{}'", pattern);
			return;
		}

		Arrays.sort(resources, Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
		ourLog.info("Preloading {} FHIR resource file(s) from '{}'", resources.length, pattern);

		RequestDetails requestDetails = newSystemRequestDetails();
		int stored = 0;
		for (Resource classpathResource : resources) {
			stored += loadAndStore(classpathResource, requestDetails);
		}
		ourLog.info("FHIR resource preload complete; stored/updated {} resource(s)", stored);
	}

	private int loadAndStore(Resource theClasspathResource, RequestDetails theRequestDetails) throws IOException {
		String description = theClasspathResource.getDescription();
		String json;
		try (InputStream in = theClasspathResource.getInputStream()) {
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}

		IBaseResource parsed = myFhirContext.newJsonParser().parseResource(json);
		String resourceType = myFhirContext.getResourceType(parsed);
		if ("Bundle".equals(resourceType)) {
			List<IBaseResource> entries = BundleUtil.toListOfResources(myFhirContext, (IBaseBundle) parsed);
			ourLog.info("Preloading Bundle '{}' with {} entr(y/ies)", description, entries.size());
			int count = 0;
			for (IBaseResource entry : entries) {
				storeOne(entry, description, theRequestDetails);
				count++;
			}
			return count;
		}

		storeOne(parsed, description, theRequestDetails);
		return 1;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void storeOne(IBaseResource theResource, String theSourceDescription, RequestDetails theRequestDetails) {
		String resourceType = myFhirContext.getResourceType(theResource);
		IFhirResourceDao dao = myDaoRegistry.getResourceDao(resourceType);
		if (dao == null) {
			throw new IllegalStateException(
					"No DAO registered for resource type '" + resourceType + "' (from " + theSourceDescription + ")");
		}

		String url = extractCanonicalUrl(theResource);
		if (theResource.getIdElement().hasIdPart()) {
			dao.update(theResource, theRequestDetails);
			ourLog.info(
					"Preloaded {}/{} from {}{}",
					resourceType,
					theResource.getIdElement().getIdPart(),
					theSourceDescription,
					url != null ? " (" + url + ")" : "");
		} else if (url != null) {
			dao.update(theResource, "url=" + UrlUtil.escapeUrlParam(url), theRequestDetails);
			ourLog.info("Preloaded {} by url={} from {}", resourceType, url, theSourceDescription);
		} else {
			dao.create(theResource, theRequestDetails);
			ourLog.info("Preloaded new {} from {}", resourceType, theSourceDescription);
		}
	}

	private String extractCanonicalUrl(IBaseResource theResource) {
		FhirTerser terser = myFhirContext.newTerser();
		return terser.getSinglePrimitiveValueOrNull(theResource, "url");
	}

	private static RequestDetails newSystemRequestDetails() {
		return new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.defaultPartition());
	}
}
