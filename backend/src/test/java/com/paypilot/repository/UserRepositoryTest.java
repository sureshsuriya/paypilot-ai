package com.paypilot.repository;

import com.paypilot.model.User;
import com.paypilot.model.UserRole;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndFindsUserByEmail() {
        User user = User.builder()
            .email("freelancer@example.com")
            .name("Alex Rivera")
            .passwordHash("$2a$10$hashedPasswordExample")
            .build();

        User saved = userRepository.saveAndFlush(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<User> found = userRepository.findByEmail("freelancer@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Alex Rivera");
        assertThat(userRepository.existsByEmail("freelancer@example.com")).isTrue();
    }

    @Test
    void enforcesUniqueEmailConstraint() {
        userRepository.saveAndFlush(User.builder()
            .email("dup@example.com")
            .name("First User")
            .passwordHash("hash1")
            .build());

        User duplicate = User.builder()
            .email("dup@example.com")
            .name("Second User")
            .passwordHash("hash2")
            .build();

        assertThatThrownBy(() -> {
            userRepository.saveAndFlush(duplicate);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidEmailFormat() {
        User invalid = User.builder()
            .email("not-an-email")
            .name("Bad Email")
            .passwordHash("hash1")
            .build();

        assertThatThrownBy(() -> {
            entityManager.persistAndFlush(invalid);
        }).isInstanceOf(ConstraintViolationException.class);
    }
}

