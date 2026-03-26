package io.autocrypt.jwlee.cowork.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.autocrypt.jwlee.cowork.BaseIntegrationTest;
import io.autocrypt.jwlee.cowork.core.dto.JiraIssueInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * JiraService의 실제 서버 연동을 확인하기 위한 통합 테스트입니다.
 * BaseIntegrationTest를 상속받아 Spring Context와 설정을 주입받습니다.
 */
class JiraServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JiraService jiraService;

    @Test
    void readIssues_realServerTest() {
        // Given
        String updatedSince = "-4w";

        // When
        List<JiraIssueInfo> result = jiraService.readIssues(updatedSince);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty(); // 실제 데이터가 조회되어야 함
        assertThat(result.get(0).key()).startsWith("VP-");
    }

    @Test
    void readIssues_specificDateTest() {
        // Given: 어제 날짜 문자열 (KST 기준)
        String yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1).toString();

        // When
        List<JiraIssueInfo> result = jiraService.readIssues(yesterday);

        // Then
        System.out.println("====== [RESULT] " + yesterday + " 기준 업데이트된 이슈 개수: " + result.size() + " 건 ======");
        if (!result.isEmpty()) {
            System.out.println("====== [RESULT] 최신 이슈 예시: " + result.get(0).key() + " - " + result.get(0).summary() + " ======");
        }

        assertThat(result).isNotNull();
        // 어제 이후로 업데이트된 이슈가 존재해야 함
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).key()).startsWith("VP-");
    }
}
