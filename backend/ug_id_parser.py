"""
Ugandan National ID PDF417 Payload Parser.
Standard-library only. No image dependencies required.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path

DATE_FORMAT = "%d%m%Y"
IDX_SURNAME = 0
IDX_GIVEN_NAME = 1
IDX_OTHER_NAME = 2
IDX_DOB = 3
IDX_ISSUED = 4
IDX_EXPIRES = 5
IDX_NIN = 6
IDX_CARD_NUMBER = 7
IDX_MINUTIAE = 8
MIN_FIELDS = 8

NIN_PATTERN = re.compile(
    r"^(?P<prefix>[A-Z])(?P<sex>[MF])(?P<yy>\d{2})(?P<serial>[0-9A-Z]{10})$"
)

SEX_CODES = {"M": "Male", "F": "Female"}
BIOMETRIC_TAG = "[FNG]"
MINUTIA_RECORD_BYTES = 5


class CardParseError(ValueError):
    """The payload could not be interpreted as a card record."""


@dataclass
class Fingerprint:
    finger_index: int | None = None
    minutiae_count: int | None = None
    minutiae_bytes: int | None = None
    sealed_block_bytes: int | None = None

    def __repr__(self) -> str:
        return (
            f"Fingerprint(finger_index={self.finger_index}, "
            f"minutiae_count={self.minutiae_count})"
        )

    def to_dict(self) -> dict:
        return {
            "finger_index": self.finger_index,
            "minutiae_count": self.minutiae_count,
            "minutiae_bytes": self.minutiae_bytes,
            "sealed_block_bytes": self.sealed_block_bytes,
        }


@dataclass
class CardRecord:
    surname: str
    given_name: str
    other_name: str
    date_of_birth: date
    issue_date: date
    expiry_date: date
    nin: str
    sex: str
    card_number: str
    fingerprint: Fingerprint = field(default_factory=Fingerprint)
    warnings: list[str] = field(default_factory=list)
    source: str | None = None
    raw: str = field(default="", repr=False)

    @property
    def full_name(self) -> str:
        return " ".join(p for p in (self.surname, self.given_name, self.other_name) if p)

    @property
    def is_expired(self) -> bool:
        return self.expiry_date < date.today()

    def age(self, on: date | None = None) -> int:
        ref = on or date.today()
        had_birthday = (ref.month, ref.day) >= (
            self.date_of_birth.month,
            self.date_of_birth.day,
        )
        return ref.year - self.date_of_birth.year - (0 if had_birthday else 1)

    def to_dict(self) -> dict:
        return {
            "surname": self.surname,
            "given_name": self.given_name,
            "other_name": self.other_name,
            "full_name": self.full_name,
            "date_of_birth": self.date_of_birth.isoformat(),
            "issue_date": self.issue_date.isoformat(),
            "expiry_date": self.expiry_date.isoformat(),
            "nin": self.nin,
            "sex": self.sex,
            "card_number": self.card_number,
            "age": self.age(),
            "is_expired": self.is_expired,
            "fingerprint": self.fingerprint.to_dict(),
            "warnings": list(self.warnings),
            "source": self.source,
        }


def _b64_decode(value: str) -> bytes:
    cleaned = re.sub(r"\s+", "", value)
    padded = cleaned + "=" * (-len(cleaned) % 4)
    if not re.fullmatch(r"[A-Za-z0-9+/]*={0,2}", padded):
        raise CardParseError("invalid base64 payload: unexpected characters")
    try:
        return base64.b64decode(padded)
    except (binascii.Error, ValueError) as exc:
        raise CardParseError(f"invalid base64 payload: {exc}") from exc


def _decode_name(value: str, label: str) -> str:
    raw = (value or "").strip()
    if not raw:
        return ""
    try:
        text = _b64_decode(raw).decode("utf-8")
    except (CardParseError, UnicodeDecodeError):
        if re.fullmatch(r"[A-Za-z '\-]+", raw):
            return raw.upper()
        raise CardParseError(f"could not decode {label} field")
    return text.strip().upper()


def _parse_date(value: str, label: str) -> date:
    raw = (value or "").strip()
    if not re.fullmatch(r"\d{8}", raw):
        raise CardParseError(f"{label} must be 8 digits (DDMMYYYY), got {raw!r}")
    try:
        return datetime.strptime(raw, DATE_FORMAT).date()
    except ValueError as exc:
        raise CardParseError(f"{label} is not a valid DDMMYYYY date: {raw!r}") from exc


def _split_sections(raw: str) -> tuple[list[str], list[str]]:
    text = (raw or "").strip()
    if not text:
        raise CardParseError("empty input")
    head, *tail = text.split(BIOMETRIC_TAG)
    return head.split(";"), tail


def _parse_fingerprint(head_blob: str, sections: list[str]) -> Fingerprint:
    fp = Fingerprint()
    if head_blob:
        try:
            fp.minutiae_bytes = len(_b64_decode(head_blob))
        except CardParseError:
            pass
    if sections:
        parts = sections[0].split(";")
        if len(parts) > 0 and parts[0].strip().isdigit():
            fp.finger_index = int(parts[0])
        if len(parts) > 1 and parts[1].strip().isdigit():
            fp.minutiae_count = int(parts[1])
        if len(parts) > 2 and parts[2].strip():
            try:
                fp.sealed_block_bytes = len(_b64_decode(parts[2]))
            except CardParseError:
                pass
    return fp


def parse_nin(nin: str) -> dict:
    match = NIN_PATTERN.match((nin or "").strip().upper())
    if not match:
        return {}
    return {
        "prefix": match.group("prefix"),
        "sex_code": match.group("sex"),
        "birth_year_short": match.group("yy"),
        "serial": match.group("serial"),
    }


def parse_card(raw: str, *, strict: bool = False, source: str | None = None) -> CardRecord:
    fields, biometric_sections = _split_sections(raw)
    if len(fields) < MIN_FIELDS:
        raise CardParseError(f"expected at least {MIN_FIELDS} fields, found {len(fields)}")

    warnings: list[str] = []

    surname = _decode_name(fields[IDX_SURNAME], "surname")
    given_name = _decode_name(fields[IDX_GIVEN_NAME], "given name")
    other_name = _decode_name(fields[IDX_OTHER_NAME], "other name")
    dob = _parse_date(fields[IDX_DOB], "date of birth")
    issued = _parse_date(fields[IDX_ISSUED], "issue date")
    expires = _parse_date(fields[IDX_EXPIRES], "expiry date")
    nin = fields[IDX_NIN].strip().upper()
    card_number = fields[IDX_CARD_NUMBER].strip()

    parts = parse_nin(nin)
    if not parts:
        warnings.append(f"NIN {nin!r} does not match the expected 14-character layout")
        sex = "Unknown"
    else:
        sex = SEX_CODES.get(parts["sex_code"], "Unknown")
        if f"{dob.year % 100:02d}" != parts["birth_year_short"]:
            warnings.append(
                f"NIN birth year '{parts['birth_year_short']}' does not match "
                f"date of birth year {dob.year}"
            )

    if issued <= dob:
        warnings.append("issue date is not after the date of birth")
    if expires <= issued:
        warnings.append("expiry date is not after the issue date")
    if expires < date.today():
        warnings.append(f"card expired on {expires.isoformat()}")

    head_blob = fields[IDX_MINUTIAE] if len(fields) > IDX_MINUTIAE else ""
    fingerprint = _parse_fingerprint(head_blob, biometric_sections)

    if (
        fingerprint.minutiae_count is not None
        and fingerprint.minutiae_bytes is not None
        and fingerprint.minutiae_bytes % MINUTIA_RECORD_BYTES != 0
    ):
        warnings.append("minutiae block length is not a multiple of the record width")

    if strict and warnings:
        raise CardParseError("; ".join(warnings))

    return CardRecord(
        surname=surname,
        given_name=given_name,
        other_name=other_name,
        date_of_birth=dob,
        issue_date=issued,
        expiry_date=expires,
        nin=nin,
        sex=sex,
        card_number=card_number,
        fingerprint=fingerprint,
        warnings=warnings,
        source=source,
        raw=(raw or "").strip(),
    )
