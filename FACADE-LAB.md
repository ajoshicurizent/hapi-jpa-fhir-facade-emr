# EMR FHIR facade lab — context from prior chat

Class handout (background, request path, classes, searches, SQL, setup): **Pure-FHIR-Facade-Lab.docx**.

This repo is the **class facade**: HAPI is the FHIR HTTP door; clinical Patient data lives in PostgreSQL database **`emrfacade`**. The cloud validator (`https://fhirserver.curizent.com`) is a **different** GitHub repo and must stay without EMR code.

## Two servers

| Project | Database | Role |
|---|---|---|
| This repo | `emrfacade` (Patient tables) + `hapi` (IG / JPA for other types) | Local facade demo |
| `hapi-fhir-jpaserver` (Droplet) | `hapi` only | `$validate`, assignments, no EMR |

Do not push this `emr` package to the Droplet repo.

## Architecture (option 1 — pure facade)

- `GET/POST/PUT /fhir/Patient` → SQL on `emrfacade.patient`, not `hfj_*`.
- `GET /fhir/Observation` → SQL on `emrfacade.vital_sign`, not `hfj_*`.
- `EmrPatientProviderRegistrar` unregisters JPA Patient and Observation, then registers `EmrPatientResourceProvider` and `EmrObservationResourceProvider`.
- We map rows to HAPI’s `org.hl7.fhir.r4.model.Patient`. **FhirContext** encodes JSON/XML and wraps `@History` / `@Search` as Bundles.
- Every interaction we want must be coded (`@Read`, `@Search` params, `@Create`, `@Update`, `@History`). HAPI does not invent them from the EMR schema.

## What is implemented

- Read, create, update. Create requires **name** (given + family) and **identifier** (MRN). Update uses URL id; MRN optional (keep existing). Gender / birthDate / active optional (defaults on create; keep existing on update).
- `meta.versionId`, `meta.lastUpdated`, `meta.source` = `urn:curizent:emr`. PUT increments `patient.version`. `If-Match` → 409 if stale.
- `_history` and `vread` from table `patient_history` (you create tables in pgAdmin; Java only writes snapshots on POST/PUT).
- Search: `GET /Patient` (all), `GET /Patient?_id=1`, `GET /Patient?identifier=` (MRN), and `GET /Patient?given=` (`given_name`, starts-with; `:exact` and `:contains` supported). Other search params (e.g. `name`, `family`) are not implemented.
- Observation (vitals): read + search only. `GET /Observation`, `?_id=`, `?patient=` / `?subject=Patient/{id}`, chained `?patient.identifier=` / `?subject.identifier=` (MRN), `?code=` (LOINC, `LN` stored as `http://loinc.org`), `?date=`. Rows map to `Observation` with category `vital-signs`, `valueQuantity` (UCUM), `subject` = `Patient/{patient_id}`. No create / update / history (table has no version).
- JDBC: `emr.datasource.url` → `jdbc:postgresql://localhost:5432/emrfacade`. YAML key `emr:` is config, not the DB name.
- `@Primary` DataSource is **`hapi`**. Without that, Hibernate created `hfj_*` inside the EMR database. Drop leftover `hfj_%` / `npm_%` / `bt2_%` / `mpi_%` / `trm_%` from `emrfacade` only, never from `hapi`.

## What is not done

- Patient `$validate` (removed with JPA Patient provider).
- Observation create / update / history.
- Honest CapabilityStatement (metadata still says HAPI FHIR Server; Patient searchParam list is over-complete).
- Option 2: HAPI store synced from EMR (later, not on the cloud box).
- Cloud deploy of this facade.

## Run locally

`mvn spring-boot:run` (avoid `clean` if you need the tester `home.html` overlay). FHIR: `http://localhost:8080/fhir`. Validate UI may be present but Patient `$validate` is broken until restored.

MRN system: `http://curizent.local/emr/mrn`.
