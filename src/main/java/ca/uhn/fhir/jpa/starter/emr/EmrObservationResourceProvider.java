package ca.uhn.fhir.jpa.starter.emr;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.starter.emr.EmrObservationRepository.EmrVitalSignRow;
import ca.uhn.fhir.jpa.starter.emr.EmrPatientRepository.EmrPatientRow;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

@Component
public class EmrObservationResourceProvider implements IResourceProvider {

	public static final String LOINC_SYSTEM = "http://loinc.org";
	public static final String UCUM_SYSTEM = "http://unitsofmeasure.org";
	public static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";

	private final EmrObservationRepository repository;
	private final EmrPatientRepository patientRepository;

	public EmrObservationResourceProvider(
			EmrObservationRepository repository, EmrPatientRepository patientRepository) {
		this.repository = repository;
		this.patientRepository = patientRepository;
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return Observation.class;
	}

	@Read
	public Observation read(@IdParam IdType id) {
		int vitalId = parseId(id);
		return repository.findById(vitalId).map(this::toFhir).orElseThrow(() -> new ResourceNotFoundException(id));
	}

	@Search
	public List<Observation> search(
			@OptionalParam(name = IAnyResource.SP_RES_ID) TokenParam id,
			@OptionalParam(
							name = Observation.SP_PATIENT,
							chainWhitelist = {OptionalParam.ALLOW_CHAIN_NOTCHAINED, Patient.SP_IDENTIFIER},
							targetTypes = Patient.class)
					ReferenceParam patient,
			@OptionalParam(
							name = Observation.SP_SUBJECT,
							chainWhitelist = {OptionalParam.ALLOW_CHAIN_NOTCHAINED, Patient.SP_IDENTIFIER},
							targetTypes = Patient.class)
					ReferenceParam subject,
			@OptionalParam(name = Observation.SP_CODE) TokenParam code,
			@OptionalParam(name = Observation.SP_DATE) DateRangeParam date) {
		Integer vitalId = parseOptionalNumericToken(id);
		if (id != null && !id.isEmpty() && vitalId == null) {
			return List.of();
		}
		Integer patientId = resolvePatientId(patient, subject);
		if (patient != null && !patient.isEmpty() && patientId == null) {
			return List.of();
		}
		if (subject != null && !subject.isEmpty() && patientId == null) {
			return List.of();
		}
		String codeSystem = null;
		String loincCode = null;
		if (code != null && !code.isEmpty()) {
			codeSystem = blankToNull(code.getSystem());
			loincCode = blankToNull(code.getValue());
			if (loincCode == null) {
				return List.of();
			}
		}
		Timestamp dateFrom = toTimestamp(date != null ? date.getLowerBoundAsInstant() : null);
		Timestamp dateTo = toTimestamp(date != null ? date.getUpperBoundAsInstant() : null);
		return repository.search(vitalId, patientId, codeSystem, loincCode, dateFrom, dateTo).stream()
				.map(this::toFhir)
				.toList();
	}

	private Observation toFhir(EmrVitalSignRow row) {
		Observation observation = new Observation();
		observation.setId(new IdType("Observation", Integer.toString(row.id())));
		observation.getMeta().setSource(EmrPatientResourceProvider.META_SOURCE);
		if (row.createdAt() != null) {
			observation.getMeta().setLastUpdated(row.createdAt());
		}
		observation.setStatus(Observation.ObservationStatus.FINAL);
		observation
				.addCategory()
				.addCoding()
				.setSystem(CATEGORY_SYSTEM)
				.setCode("vital-signs")
				.setDisplay("Vital Signs");
		observation
				.getCode()
				.addCoding()
				.setSystem(toFhirCodeSystem(row.codeSystem()))
				.setCode(row.loincCode())
				.setDisplay(row.display());
		observation.setSubject(new Reference("Patient/" + row.patientId()));
		if (row.effectiveAt() != null) {
			observation.setEffective(new DateTimeType(row.effectiveAt()));
		}
		Quantity quantity = new Quantity();
		quantity.setValue(row.valueNum());
		quantity.setUnit(row.unit());
		quantity.setSystem(UCUM_SYSTEM);
		quantity.setCode(row.unit());
		observation.setValue(quantity);
		return observation;
	}

	private static String toFhirCodeSystem(String codeSystem) {
		if (codeSystem == null || codeSystem.isBlank()) {
			return LOINC_SYSTEM;
		}
		if ("LN".equalsIgnoreCase(codeSystem) || "LOINC".equalsIgnoreCase(codeSystem)) {
			return LOINC_SYSTEM;
		}
		return codeSystem;
	}

	private Integer resolvePatientId(ReferenceParam patient, ReferenceParam subject) {
		Integer fromPatient = patientIdFrom(patient);
		Integer fromSubject = patientIdFrom(subject);
		if (fromPatient != null && fromSubject != null && !fromPatient.equals(fromSubject)) {
			return null;
		}
		return fromPatient != null ? fromPatient : fromSubject;
	}

	private Integer patientIdFrom(ReferenceParam ref) {
		if (ref == null || ref.isEmpty()) {
			return null;
		}
		String chain = ref.getChain();
		if (chain != null && !chain.isEmpty()) {
			if (!Patient.SP_IDENTIFIER.equals(chain)) {
				return null;
			}
			return patientIdFromIdentifier(ref);
		}
		String type = ref.getResourceType();
		if (type != null && !type.isEmpty() && !"Patient".equals(type)) {
			return null;
		}
		String idPart = ref.getIdPart();
		if (idPart == null || !idPart.matches("[0-9]+")) {
			return null;
		}
		return Integer.parseInt(idPart);
	}

	private Integer patientIdFromIdentifier(ReferenceParam ref) {
		String raw = ref.getValue();
		if (raw == null || raw.isBlank()) {
			raw = ref.getIdPart();
		}
		if (raw == null || raw.isBlank()) {
			return null;
		}
		TokenParam token = new TokenParam();
		token.setValueAsQueryToken(null, null, null, raw);
		String system = blankToNull(token.getSystem());
		String mrn = blankToNull(token.getValue());
		if (mrn == null) {
			return null;
		}
		if (system != null && !EmrPatientResourceProvider.MRN_SYSTEM.equals(system)) {
			return null;
		}
		return patientRepository.findByMrn(mrn).map(EmrPatientRow::id).orElse(null);
	}

	private static Integer parseOptionalNumericToken(TokenParam id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		String value = id.getValue();
		if (value == null || !value.matches("[0-9]+")) {
			return null;
		}
		return Integer.parseInt(value);
	}

	private static Timestamp toTimestamp(Date date) {
		return date == null ? null : new Timestamp(date.getTime());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static int parseId(IdType id) {
		if (id == null || !id.hasIdPart() || !id.getIdPart().matches("[0-9]+")) {
			throw new ResourceNotFoundException(id);
		}
		return Integer.parseInt(id.getIdPart());
	}
}
