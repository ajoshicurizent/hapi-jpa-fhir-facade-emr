package ca.uhn.fhir.jpa.starter.emr;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmrDataSourceConfig {

	private static final Logger log = LoggerFactory.getLogger(EmrDataSourceConfig.class);

	private static DataSource buildDataSource(String url, String username, String password, String driverClassName) {
		return DataSourceBuilder.create()
				.url(url)
				.username(username)
				.password(password)
				.driverClassName(driverClassName)
				.build();
	}

	@Bean
	@Primary
	public DataSource dataSource(
			@Value("${spring.datasource.url}") String url,
			@Value("${spring.datasource.username}") String username,
			@Value("${spring.datasource.password}") String password,
			@Value("${spring.datasource.driver-class-name}") String driverClassName) {
		return buildDataSource(url, username, password, driverClassName);
	}

	@Bean(name = "emrDataSource")
	public DataSource emrDataSource(
			@Value("${emr.datasource.url}") String url,
			@Value("${emr.datasource.username}") String username,
			@Value("${emr.datasource.password}") String password,
			@Value("${emr.datasource.driver-class-name}") String driverClassName) {
		return buildDataSource(url, username, password, driverClassName);
	}

	@Bean
	ApplicationRunner emrConnectionCheck(@Qualifier("emrDataSource") DataSource emrDataSource) {
		return args -> {
			try (Connection connection = emrDataSource.getConnection();
					Statement statement = connection.createStatement()) {
				int patients = count(statement, "SELECT COUNT(*) FROM patient");
				int vitals = count(statement, "SELECT COUNT(*) FROM vital_sign");
				log.info("EMR facade database connected. patient rows={}, vital_sign rows={}", patients, vitals);
			}
		};
	}

	private static int count(Statement statement, String sql) throws SQLException {
		try (ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}
}