package ca.uhn.fhir.jpa.starter.emr;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
public class EmrObservationRepository {

	private static final String SELECT_COLUMNS =
			"id, patient_id, code_system, loinc_code, display, value_num, unit, effective_at, created_at";

	private final DataSource emrDataSource;

	public EmrObservationRepository(@Qualifier("emrDataSource") DataSource emrDataSource) {
		this.emrDataSource = emrDataSource;
	}

	public Optional<EmrVitalSignRow> findById(int id) {
		String sql = "SELECT " + SELECT_COLUMNS + " FROM vital_sign WHERE id = ?";
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
			throw new IllegalStateException("Failed to read EMR vital sign " + id, e);
		}
	}

	public List<EmrVitalSignRow> search(
			Integer id, Integer patientId, String codeSystem, String code, Timestamp dateFrom, Timestamp dateTo) {
		StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS).append(" FROM vital_sign WHERE 1=1");
		List<Object> params = new ArrayList<>();
		if (id != null) {
			sql.append(" AND id = ?");
			params.add(id);
		}
		if (patientId != null) {
			sql.append(" AND patient_id = ?");
			params.add(patientId);
		}
		if (code != null && !code.isBlank()) {
			sql.append(" AND loinc_code = ?");
			params.add(code);
		}
		if (codeSystem != null && !codeSystem.isBlank()) {
			if ("http://loinc.org".equals(codeSystem)) {
				sql.append(" AND (code_system = ? OR UPPER(code_system) IN ('LN', 'LOINC'))");
				params.add(codeSystem);
			} else {
				sql.append(" AND code_system = ?");
				params.add(codeSystem);
			}
		}
		if (dateFrom != null) {
			sql.append(" AND effective_at >= ?");
			params.add(dateFrom);
		}
		if (dateTo != null) {
			sql.append(" AND effective_at <= ?");
			params.add(dateTo);
		}
		sql.append(" ORDER BY effective_at DESC, id DESC");

		List<EmrVitalSignRow> rows = new ArrayList<>();
		try (Connection connection = emrDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql.toString())) {
			for (int i = 0; i < params.size(); i++) {
				statement.setObject(i + 1, params.get(i));
			}
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(map(resultSet));
				}
			}
			return rows;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to search EMR vital signs", e);
		}
	}

	private static EmrVitalSignRow map(ResultSet resultSet) throws SQLException {
		return new EmrVitalSignRow(
				resultSet.getInt("id"),
				resultSet.getInt("patient_id"),
				resultSet.getString("code_system"),
				resultSet.getString("loinc_code"),
				resultSet.getString("display"),
				resultSet.getBigDecimal("value_num"),
				resultSet.getString("unit"),
				resultSet.getTimestamp("effective_at"),
				resultSet.getTimestamp("created_at"));
	}

	public record EmrVitalSignRow(
			int id,
			int patientId,
			String codeSystem,
			String loincCode,
			String display,
			BigDecimal valueNum,
			String unit,
			Timestamp effectiveAt,
			Timestamp createdAt) {}
}
