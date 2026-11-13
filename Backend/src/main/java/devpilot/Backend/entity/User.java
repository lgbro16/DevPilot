package devpilot.Backend.entity;

import java.time.Instant;
import java.util.UUID;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
@Entity
@Getter
@Setter 
@NoArgsConstructor
@Table (name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "github_id", unique = true, nullable = false)
    private Long githubId;
    @Column(name = "github_username", unique = true, nullable = false)
    private String githubUsername;
    @Column(name = "display_name", nullable = false , length = 100)
    private String displayName;
    @Column(name = "avatar_url",length = 500)
    private String avatarUrl;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "access_token", length = 500,columnDefinition = "TEXT")
    private String accessToken;
    @Column(name = "access_scope", length = 500)
    private String accessScope;

    public String getTokenScopes() {
        return accessScope;
    }

    public void setTokenScopes(String scopes) {
        this.accessScope = scopes;
    }
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}