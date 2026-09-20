/**
 * 별도 프로세스로 기동하는 Mock PG 서버 (1.5, 1.6). 순수 JDK HttpServer 기반이며
 * Spring 컴포넌트가 없어 메인 애플리케이션 컨텍스트에 절대 포함되지 않는다.
 * {@code ./gradlew mockPgRun}으로 기동한다.
 */
package org.example.cs_study.mockpg;
