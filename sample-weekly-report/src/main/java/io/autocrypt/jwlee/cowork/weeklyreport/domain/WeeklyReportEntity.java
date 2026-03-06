package io.autocrypt.jwlee.cowork.weeklyreport.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "weekly_reports")
public class WeeklyReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime createdAt;

    public WeeklyReportEntity() {}

    public WeeklyReportEntity(Long id, String title, String content, LocalDateTime createdAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
    }

    public static WeeklyReportEntityBuilder builder() {
        return new WeeklyReportEntityBuilder();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static class WeeklyReportEntityBuilder {
        private Long id;
        private String title;
        private String content;
        private LocalDateTime createdAt;

        public WeeklyReportEntityBuilder id(Long id) { this.id = id; return this; }
        public WeeklyReportEntityBuilder title(String title) { this.title = title; return this; }
        public WeeklyReportEntityBuilder content(String content) { this.content = content; return this; }
        public WeeklyReportEntityBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public WeeklyReportEntity build() {
            return new WeeklyReportEntity(id, title, content, createdAt);
        }
    }
}
