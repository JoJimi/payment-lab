package org.example.cs_study.order;

import io.lettuce.core.RedisClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0단계 완료 기준 검증
 * - Spring 컨텍스트 없이 순수 Testcontainers로 실행 (Boot 4 호환)
 */
@Testcontainers
class ContainerConnectivityTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_lab_test")
            .withUsername("cs")
            .withPassword("cs123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Test
    void postgres_연결_및_Flyway_마이그레이션_확인() throws Exception {
        // Flyway 마이그레이션 실행
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // V1__init.sql 에서 생성한 테이블과 데이터 확인
        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {

            var rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_migration_log");
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void redis_PING_확인() {
        String uri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        try (var client = RedisClient.create(uri);
             var conn = client.connect()) {
            assertThat(conn.sync().ping()).isEqualTo("PONG");
        }
    }
}
