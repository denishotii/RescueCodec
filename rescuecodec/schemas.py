"""Prototype emergency schemas. These identifiers are project-local, not standards."""
from __future__ import annotations

from enum import IntEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Consciousness(IntEnum):
    UNKNOWN = 0
    CONSCIOUS = 1
    UNCONSCIOUS = 2
    NOT_APPLICABLE = 3


class Breathing(IntEnum):
    UNKNOWN = 0
    YES = 1
    NO = 2
    NOT_APPLICABLE = 3


class Urgency(IntEnum):
    UNKNOWN = 0
    ROUTINE = 1
    URGENT = 2
    CRITICAL = 3


class Assistance(IntEnum):
    UNKNOWN = 0
    NONE = 1
    MEDICAL = 2
    SEARCH = 3
    EXTRACTION = 4
    FIRE = 5
    OTHER = 6


class Equipment(IntEnum):
    OXYGEN_MASK = 0
    FIRST_AID_KIT = 1
    AED = 2
    STRETCHER = 3
    OXYGEN = 4
    WATER = 5
    RADIO = 6
    OTHER = 7


class PersonFoundReport(BaseModel):
    """Schema 6, version 1. Unknown/missing facts remain explicit."""

    model_config = ConfigDict(extra="forbid", validate_assignment=True)

    schema_id: Literal[6] = 6
    event: Literal["PERSON_FOUND"] = "PERSON_FOUND"
    consciousness: Consciousness = Consciousness.UNKNOWN
    breathing: Breathing = Breathing.UNKNOWN
    equipment_needed: list[Equipment] = Field(default_factory=list, max_length=8)
    request: Assistance = Assistance.UNKNOWN
    urgency: Urgency = Urgency.UNKNOWN
    location: tuple[float, float] | None = None
    location_quality: Literal[0, 1, 2, 3] = 0  # unknown, approximate, reported, GPS
    person_count: int | None = Field(default=None, ge=1, le=15)
    responders_needed_min: int | None = Field(default=None, ge=1, le=15)
    source_text: str | None = Field(default=None, exclude=True)
    unsupported_reason: str | None = Field(default=None, exclude=True)

    @field_validator("location")
    @classmethod
    def valid_coordinates(cls, value):
        if value is not None:
            lat, lon = value
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                raise ValueError("coordinates outside latitude/longitude bounds")
        return value


class PersonnelStatus(BaseModel):
    schema_id: Literal[1] = 1
    status: str


class HazardReport(BaseModel):
    schema_id: Literal[2] = 2
    hazard: str


class ResourceRequest(BaseModel):
    schema_id: Literal[3] = 3
    resource: str


SCHEMA_REGISTRY = {1: "personnel_status", 2: "hazard_report", 3: "resource_request", 6: "person_found"}
