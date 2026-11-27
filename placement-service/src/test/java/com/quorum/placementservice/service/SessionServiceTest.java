package com.quorum.placementservice.service;

import com.quorum.placementservice.config.Topics;
import com.quorum.placementservice.dto.SessionRequest;
import com.quorum.placementservice.dto.SessionResponse;
import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.entity.Round;
import com.quorum.placementservice.entity.Session;
import com.quorum.placementservice.entity.SessionStatus;
import com.quorum.placementservice.event.DriveEventFactory;
import com.quorum.placementservice.event.EventTypes;
import com.quorum.placementservice.event.SessionEvent;
import com.quorum.placementservice.repository.RoundRepository;
import com.quorum.placementservice.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private static final LocalDate DRIVE_DAY = LocalDate.of(2026, 12, 1);

    private RoundRepository rounds;
    private SessionRepository sessions;
    private OutboxService outbox;
    private SessionService service;
    private Round round;

    @BeforeEach
    void setUp() {
        rounds = mock(RoundRepository.class);
        sessions = mock(SessionRepository.class);
        outbox = mock(OutboxService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T10:15:30Z"), ZoneOffset.UTC);
        service = new SessionService(rounds, sessions, outbox, new DriveEventFactory(clock));

        Drive drive = new Drive();
        drive.setId(5L);
        drive.setDriveDate(DRIVE_DAY);
        round = new Round();
        round.setId(11L);
        round.setDrive(drive);
        round.setName("Technical");
        round.setSequence(2);

        when(sessions.save(any(Session.class))).thenAnswer(inv -> {
            Session s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(21L);
            }
            return s;
        });
    }

    private Session existing(SessionStatus status) {
        Session s = new Session();
        s.setId(21L);
        s.setRound(round);
        s.setDate(DRIVE_DAY);
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(10, 0));
        s.setRoom("Lab 1");
        s.setStatus(status);
        when(sessions.findById(21L)).thenReturn(Optional.of(s));
        return s;
    }

    private SessionEvent capturedEvent(String type) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(Topics.DRIVE_EVENTS), eq("5"), eq(type), event.capture());
        return (SessionEvent) event.getValue();
    }

    @Test
    void createIsScheduledOnAnyDateAndWritesEventKeyedByDrive() {
        when(rounds.findById(11L)).thenReturn(Optional.of(round));
        LocalDate nextDay = DRIVE_DAY.plusDays(1);

        SessionResponse created = service.create(11L,
                new SessionRequest(nextDay, LocalTime.of(9, 0), LocalTime.of(10, 30), " Lab 2 "));

        assertThat(created.status()).isEqualTo(SessionStatus.SCHEDULED);
        assertThat(created.date()).isEqualTo(nextDay);
        assertThat(created.room()).isEqualTo("Lab 2");
        SessionEvent e = capturedEvent(EventTypes.SESSION_SCHEDULED);
        assertThat(e.eventType()).isEqualTo("SESSION_SCHEDULED");
        assertThat(e.schemaVersion()).isEqualTo(1);
        assertThat(e.occurredAt()).isEqualTo("2026-11-27T10:15:30.000Z");
        assertThat(e.eventId()).hasSize(36);
        assertThat(e.driveId()).isEqualTo(5L);
        assertThat(e.roundId()).isEqualTo(11L);
        assertThat(e.sessionId()).isEqualTo(21L);
        assertThat(e.date()).isEqualTo("2026-12-02");
        assertThat(e.startTime()).isEqualTo("09:00:00");
        assertThat(e.endTime()).isEqualTo("10:30:00");
        assertThat(e.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void createInUnknownRoundIs404() {
        when(rounds.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(99L,
                new SessionRequest(DRIVE_DAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Lab 1")))
                .isInstanceOf(NotFoundException.class);
        verify(sessions, never()).save(any());
        verify(outbox, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    void rescheduleUpdatesAndWritesSessionUpdated() {
        existing(SessionStatus.SCHEDULED);

        SessionResponse r = service.reschedule(21L,
                new SessionRequest(DRIVE_DAY, LocalTime.of(14, 0), LocalTime.of(15, 0), "Lab 3"));

        assertThat(r.startTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(r.room()).isEqualTo("Lab 3");
        SessionEvent e = capturedEvent(EventTypes.SESSION_UPDATED);
        assertThat(e.startTime()).isEqualTo("14:00:00");
        assertThat(e.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void rescheduleWithNoChangeWritesNoEvent() {
        existing(SessionStatus.SCHEDULED);
        service.reschedule(21L, new SessionRequest(DRIVE_DAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Lab 1"));
        verify(outbox, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelledSessionCannotBeRescheduled() {
        existing(SessionStatus.CANCELLED);
        assertThatThrownBy(() -> service.reschedule(21L,
                new SessionRequest(DRIVE_DAY, LocalTime.of(14, 0), LocalTime.of(15, 0), "Lab 3")))
                .isInstanceOf(ConflictException.class);
        verify(outbox, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelSetsStatusAndWritesSessionCancelled() {
        Session s = existing(SessionStatus.SCHEDULED);

        SessionResponse r = service.cancel(21L);

        assertThat(r.status()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.CANCELLED);
        SessionEvent e = capturedEvent(EventTypes.SESSION_CANCELLED);
        assertThat(e.status()).isEqualTo("CANCELLED");
        assertThat(e.date()).isEqualTo("2026-12-01");
        assertThat(e.room()).isEqualTo("Lab 1");
    }

    @Test
    void cancellingTwiceIs409() {
        existing(SessionStatus.CANCELLED);
        assertThatThrownBy(() -> service.cancel(21L)).isInstanceOf(ConflictException.class);
        verify(outbox, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    void unknownSessionIs404() {
        when(sessions.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(404L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(404L)).isInstanceOf(NotFoundException.class);
    }
}
