package com.quorum.checkinservice.controller;

import com.quorum.checkinservice.config.TemporaryAuthInterceptor;
import com.quorum.checkinservice.dto.CardImportResponse;
import com.quorum.checkinservice.dto.CardRequest;
import com.quorum.checkinservice.dto.CardResponse;
import com.quorum.checkinservice.dto.UnknownTapResponse;
import com.quorum.checkinservice.service.ApiException;
import com.quorum.checkinservice.service.CardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class CardController {

    static final long MAX_CSV_BYTES = 2_000_000;

    private final CardService cards;

    public CardController(CardService cards) {
        this.cards = cards;
    }

    /** 201 when enrolled now, 200 when this card was already enrolled to this student. */
    @PostMapping("/cards")
    public ResponseEntity<CardResponse> enrol(@RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                              @Valid @RequestBody CardRequest request) {
        CardService.Enrolled e = cards.enrol(request.studentId(), request.cardUid(), userId);
        return ResponseEntity.status(e.created() ? HttpStatus.CREATED : HttpStatus.OK).body(CardResponse.from(e.card()));
    }

    /** multipart/form-data with a {@code file} part: CSV {@code studentId,cardUid}. */
    @PostMapping(value = "/cards/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CardImportResponse importCsv(@RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("CSV_INVALID", "file is empty");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw ApiException.badRequest("CSV_INVALID", "file is larger than " + MAX_CSV_BYTES + " bytes");
        }
        CardService.Imported r = cards.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8), userId);
        return new CardImportResponse(r.imported(), r.unchanged(), r.total());
    }

    @GetMapping("/cards")
    public List<CardResponse> list(@RequestParam(required = false) String studentId) {
        return cards.list(studentId).stream().map(CardResponse::from).toList();
    }

    /** Cards that were tapped but are not enrolled, most recent first. */
    @GetMapping("/cards/unknown-taps")
    public List<UnknownTapResponse> unknownTaps() {
        return cards.unknownTaps().stream().map(UnknownTapResponse::from).toList();
    }
}
