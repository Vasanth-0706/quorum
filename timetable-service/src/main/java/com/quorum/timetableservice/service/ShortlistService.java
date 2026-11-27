package com.quorum.timetableservice.service;

import com.quorum.timetableservice.entity.ShortlistEntry;
import com.quorum.timetableservice.event.EventFields;
import com.quorum.timetableservice.repository.ShortlistEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.TreeSet;

/** SHORTLIST_UPLOADED: the round's shortlist is replaced as a whole. */
@Service
public class ShortlistService {

    private final ShortlistEntryRepository repository;

    public ShortlistService(ShortlistEntryRepository repository) {
        this.repository = repository;
    }

    /** @return the roundId whose shortlist was replaced */
    @Transactional(propagation = Propagation.MANDATORY)
    public long replace(JsonNode event) {
        long roundId = EventFields.id(event, "roundId");
        String uploadId = EventFields.text(event, "uploadId", 64);
        Set<String> studentIds = new TreeSet<>();
        for (JsonNode n : EventFields.array(event, "studentIds")) {
            studentIds.add(EventFields.studentId(n.isNull() ? null : n.asString().trim()));
        }
        repository.deleteByRoundId(roundId);
        repository.saveAll(studentIds.stream().map(id -> new ShortlistEntry(roundId, id, uploadId)).toList());
        return roundId;
    }
}
