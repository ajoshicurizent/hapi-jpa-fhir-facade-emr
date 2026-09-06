package ca.uhn.fhir.jpa.starter.emr;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.starter.emr.EmrPatientRepository.EmrPatientRow;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.History;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

@Component
public class EmrPatientResourceProvider implements IResourceProvider {

	public static final String MRN_SYSTEM = "http://curizent.local/emr/mrn";
	public static final String META_SOURCE = "urn:curizent:emr";

	private final EmrPatientRepository repository;

	public EmrPatientResourceProvider(EmrPatientRepository repository) {
		this.repository = repository;
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return Patient.class;
	}

	@Read(version = true)
	public Patient read(@IdParam IdType id) {
		int emrId = parseId(id);
		if (id.hasVersionIdPart()) {
			int version = parseVersion(id);
			return repository
					.findVersion(emrId, version)
					.map(this::toFhir)
					.orElseThrow(() -> new ResourceNotFoundException(id));
		}
		return repository.findById(emrId).map(this::toFhir).orElseThrow(() -> new ResourceNotFoundException(id));
	}

	@History
	public List<Patient> history(@IdParam IdType id) {
		int emrId = parseId(id);
		if (repository.findById(emrId).isEmpty()) {
			throw new ResourceNotFoundException(id);
		}
		return repository.findHistory(emrId).stream().map(this::toFhir).toList();
	}

	@Search
	public List<Patient> search(
			@OptionalParam(name = IAnyResource.SP_RES_ID) TokenParam id,
			@OptionalParam(name = Patient.SP_IDENTIFIER) TokenParam identifier,
			@OptionalParam(name = Patient.SP_GIVEN) StringParam given) {
		final Integer emrId;
		if (id != null && !id.isEmpty()) {
			String value = id.getValue();
			if (value == null || !value.matches("[0-9]+")) {
				return List.of();
			}
			emrId = Integer.parseInt(value);
		} else {
			emrId = null;
		}
		final String mrn;
		if (identifier != null && !identifier.isEmpty()) {
			String system = identifier.getSystem();
			mrn = identifier.getValue();
			if (mrn == null || mrn.isBlank()) {
				return List.of();
			}
			if (system != null && !system.isBlank() && !MRN_SYSTEM.equals(system)) {
				return List.of();
			}
		} else {
			mrn = null;
		}
		List<EmrPatientRow> rows;
		if (given != null && !given.isEmpty() && given.getValue() != null && !given.getValue().isBlank()) {
			boolean contains = ":contains".equals(given.getQueryParameterQualifier());
			rows = repository.findByGiven(given.getValue().trim(), given.isExact(), contains);
		} else if (mrn != null) {
			rows = repository.findByMrn(mrn).map(List::of).orElseGet(List::of);
		} else if (emrId != null) {
			rows = repository.findById(emrId).map(List::of).orElseGet(List::of);
		} else {
			rows = repository.findAll();
		}
		return rows.stream()
				.filter(row -> emrId == null || row.id() == emrId)
				.filter(row -> mrn == null || mrn.equals(row.mrn()))
				.map(this::toFhir)
				.toList();
	}

	@Create
	public MethodOutcome create(@ResourceParam Patient patient) {
		Parsed parsed = parse(patient, null);
		int newId =
				repository.insert(parsed.mrn, parsed.given, parsed.family, parsed.gender, parsed.birthDate, parsed.active);
		Patient created = repository.findById(newId).map(this::toFhir).orElseThrow();
		MethodOutcome outcome = new MethodOutcome();
		outcome.setCreated(true);
		outcome.setId(created.getIdElement());
		outcome.setResource(created);
		return outcome;
	}

	@Update
	public MethodOutcome update(
			@IdParam IdType id, @ResourceParam Patient patient, RequestDetails requestDetails) {
		int emrId = parseId(id);
		EmrPatientRow existing =
				repository.findById(emrId).orElseThrow(() -> new ResourceNotFoundException(id));
		assertIfMatch(requestDetails, existing.version());
		Parsed parsed = parse(patient, existing);
		repository.update(emrId, parsed.mrn, parsed.given, parsed.family, parsed.gender, parsed.birthDate, parsed.active);
		Patient updated = repository.findById(emrId).map(this::toFhir).orElseThrow();
		MethodOutcome outcome = new MethodOutcome();
		outcome.setId(updated.getIdElement());
		outcome.setResource(updated);
		return outcome;
	}

	private Patient toFhir(EmrPatientRow row) {
		Patient patient = new Patient();
		patient.setId(new IdType("Patient", Integer.toString(row.id()), Integer.toString(row.version())));
		patient.getMeta().setVersionId(Integer.toString(row.version()));
		patient.getMeta().setSource(META_SOURCE);
		if (row.updatedAt() != null) {
			patient.getMeta().setLastUpdated(row.updatedAt());
		}
		patient.addIdentifier().setSystem(MRN_SYSTEM).setValue(row.mrn());
		patient.addName().setFamily(row.familyName()).addGiven(row.givenName());
		AdministrativeGender gender = AdministrativeGender.fromCode(row.gender());
		patient.setGender(gender != null ? gender : AdministrativeGender.UNKNOWN);
		if (row.birthDate() != null) {
			patient.setBirthDate(row.birthDate());
		}
		patient.setActive(row.active());
		return patient;
	}

	private Parsed parse(Patient patient, EmrPatientRow existing) {
		boolean create = existing == null;
		HumanName name = patient.hasName() ? patient.getNameFirstRep() : null;
		String given = name != null && name.hasGiven() ? name.getGivenAsSingleString() : "";
		String family = name != null && name.hasFamily() ? name.getFamily() : "";
		if (given.isBlank() || family.isBlank()) {
			throw new UnprocessableEntityException("Patient.name given and family are required");
		}
		String mrn = extractMrn(patient);
		if (mrn == null || mrn.isBlank()) {
			if (create) {
				throw new UnprocessableEntityException("Patient.identifier (MRN) is required");
			}
			mrn = existing.mrn();
		}
		String gender = patient.hasGender()
				? patient.getGender().toCode()
				: (create ? "unknown" : existing.gender());
		Date birthDate;
		if (patient.hasBirthDate()) {
			birthDate = Date.valueOf(LocalDate.parse(patient.getBirthDateElement().getValueAsString()));
		} else {
			birthDate = create ? Date.valueOf(LocalDate.of(1900, 1, 1)) : existing.birthDate();
		}
		boolean active = patient.hasActive() ? patient.getActive() : (create ? true : existing.active());
		return new Parsed(mrn, given, family, gender, birthDate, active);
	}

	private static String extractMrn(Patient patient) {
		for (Identifier identifier : patient.getIdentifier()) {
			if (MRN_SYSTEM.equals(identifier.getSystem()) && identifier.hasValue()) {
				return identifier.getValue();
			}
		}
		if (patient.hasIdentifier() && patient.getIdentifierFirstRep().hasValue()) {
			return patient.getIdentifierFirstRep().getValue();
		}
		return null;
	}

	private static void assertIfMatch(RequestDetails requestDetails, int currentVersion) {
		if (requestDetails == null) {
			return;
		}
		String ifMatch = requestDetails.getHeader(Constants.HEADER_IF_MATCH);
		if (ifMatch == null || ifMatch.isBlank()) {
			return;
		}
		int expected = parseIfMatchVersion(ifMatch);
		if (expected != currentVersion) {
			throw new ResourceVersionConflictException(
					"If-Match version " + expected + " does not match current version " + currentVersion);
		}
	}

	private static int parseIfMatchVersion(String ifMatch) {
		String value = ifMatch.trim();
		if (value.regionMatches(true, 0, "W/", 0, 2)) {
			value = value.substring(2).trim();
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		if (!value.matches("[0-9]+")) {
			throw new UnprocessableEntityException("If-Match must be a numeric version, e.g. W/\"1\"");
		}
		return Integer.parseInt(value);
	}

	private static int parseId(IdType id) {
		if (id == null || !id.hasIdPart() || !id.getIdPart().matches("[0-9]+")) {
			throw new ResourceNotFoundException(id);
		}
		return Integer.parseInt(id.getIdPart());
	}

	private static int parseVersion(IdType id) {
		if (id == null || !id.hasVersionIdPart() || !id.getVersionIdPart().matches("[0-9]+")) {
			throw new ResourceNotFoundException(id);
		}
		return Integer.parseInt(id.getVersionIdPart());
	}

	private record Parsed(String mrn, String given, String family, String gender, Date birthDate, boolean active) {}
}
