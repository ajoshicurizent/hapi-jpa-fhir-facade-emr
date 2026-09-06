package ca.uhn.fhir.jpa.starter.emr;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
public class EmrPatientRepository {

	private final DataSource emrDataSource;

	public EmrPatientRepository(@Qualifier("emrDataSource") DataSource emrDataSource) {
		this.emrDataSource = emrDataSource;
	}

	private static final String SELECT_COLUMNS =
			"id, mrn, given_name, family_name, gender, birth_date, active, version, updated_at";
	private static final String SELECT_HISTORY_COLUMNS =
			"patient_id AS id, mrn, given_name, family_name, gender, birth_date, active, version, updated_at";
	private static final String COPY_HISTORY_SQL =
			"""
			INSERT INTO patient_history
				(patient_id, version, mrn, given_name, family_name, gender, birth_date, active, updated_at)
			SELECT id, version, mrn, given_name, family_name, gender, birth_date, active, updated_at
			FROM patient WHERE id = ?
			ON CONFLICT DO NOTHING
			""";

	public Optional<EmrPatientRow> findById(int id) {
		String sql = "SELECT " + SELECT_COLUMNS + " FROM patient WHERE id = ?";
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setInt(1, id);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (resultSet.next()) {
					return Optional.of(map(resultSet));
				}
				return Optional.empty();
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to read EMR patient " + id, e);
		}
	}

	public Optional<EmrPatientRow> findByMrn(String mrn) {
		String sql = "SELECT " + SELECT_COLUMNS + " FROM patient WHERE mrn = ?";
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, mrn);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (resultSet.next()) {
					return Optional.of(map(resultSet));
				}
				return Optional.empty();
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to read EMR patient by MRN", e);
		}
	}

	public List<EmrPatientRow> findByGiven(String given, boolean exact, boolean contains) {
		String sql;
		if (exact) {
			sql = "SELECT " + SELECT_COLUMNS + " FROM patient WHERE LOWER(given_name) = LOWER(?) ORDER BY id";
		} else if (contains) {
			sql = "SELECT " + SELECT_COLUMNS + " FROM patient WHERE given_name ILIKE ? ESCAPE '\\' ORDER BY id";
		} else {
			sql = "SELECT " + SELECT_COLUMNS + " FROM patient WHERE given_name ILIKE ? ESCAPE '\\' ORDER BY id";
		}
		String pattern = exact ? given : (contains ? "%" + escapeLike(given) + "%" : escapeLike(given) + "%");
		List<EmrPatientRow> rows = new ArrayList<>();
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, pattern);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(map(resultSet));
				}
			}
			return rows;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to search EMR patients by given name", e);
		}
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	public List<EmrPatientRow> findAll() {
		String sql = "SELECT " + SELECT_COLUMNS + " FROM patient ORDER BY id";
		List<EmrPatientRow> rows = new ArrayList<>();
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				rows.add(map(resultSet));
			}
			return rows;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to search EMR patients", e);
		}
	}

	public int insert(String mrn, String given, String family, String gender, Date birthDate, boolean active) {
		String sql =
				"INSERT INTO patient (mrn, given_name, family_name, gender, birth_date, active) VALUES (?,?,?,?,?,?)";
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, mrn);
			statement.setString(2, given);
			statement.setString(3, family);
			statement.setString(4, gender);
			statement.setDate(5, birthDate);
			statement.setBoolean(6, active);
			statement.executeUpdate();
			int newId;
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("Insert did not return a patient id");
				}
				newId = keys.getInt(1);
			}
			copyCurrentToHistory(connection, newId);
			return newId;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to insert EMR patient", e);
		}
	}

	public boolean update(
			int id, String mrn, String given, String family, String gender, Date birthDate, boolean active) {
		String sql =
				"UPDATE patient SET mrn=?, given_name=?, family_name=?, gender=?, birth_date=?, active=?,"
						+ " version = version + 1, updated_at = NOW() WHERE id=?";
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, mrn);
			statement.setString(2, given);
			statement.setString(3, family);
			statement.setString(4, gender);
			statement.setDate(5, birthDate);
			statement.setBoolean(6, active);
			statement.setInt(7, id);
			boolean updated = statement.executeUpdate() == 1;
			if (updated) {
				copyCurrentToHistory(connection, id);
			}
			return updated;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to update EMR patient " + id, e);
		}
	}

	public List<EmrPatientRow> findHistory(int id) {
		String sql = "SELECT " + SELECT_HISTORY_COLUMNS + " FROM patient_history WHERE patient_id = ? ORDER BY version DESC";
		List<EmrPatientRow> rows = new ArrayList<>();
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setInt(1, id);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(map(resultSet));
				}
			}
			return rows;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to read EMR patient history " + id, e);
		}
	}

	public Optional<EmrPatientRow> findVersion(int id, int version) {
		String sql = "SELECT " + SELECT_HISTORY_COLUMNS + " FROM patient_history WHERE patient_id = ? AND version = ?";
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setInt(1, id);
			statement.setInt(2, version);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (resultSet.next()) {
					return Optional.of(map(resultSet));
				}
				return Optional.empty();
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to read EMR patient " + id + " version " + version, e);
		}
	}

	private static void copyCurrentToHistory(Connection connection, int id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(COPY_HISTORY_SQL)) {
			statement.setInt(1, id);
			statement.executeUpdate();
		}
	}

	private static EmrPatientRow map(ResultSet resultSet) throws SQLException {
		return new EmrPatientRow(
				resultSet.getInt("id"),
				resultSet.getString("mrn"),
				resultSet.getString("given_name"),
				resultSet.getString("family_name"),
				resultSet.getString("gender"),
				resultSet.getDate("birth_date"),
				resultSet.getBoolean("active"),
				resultSet.getInt("version"),
				resultSet.getTimestamp("updated_at"));
	}

	public record EmrPatientRow(
			int id,
			String mrn,
			String givenName,
			String familyName,
			String gender,
			Date birthDate,
			boolean active,
			int version,
			Timestamp updatedAt) {}
}
