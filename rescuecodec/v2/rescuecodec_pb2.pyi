from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Accuracy(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ACCURACY_GPS: _ClassVar[Accuracy]
    ACCURACY_GPS_POOR: _ClassVar[Accuracy]
    ACCURACY_ESTIMATED: _ClassVar[Accuracy]
    ACCURACY_AREA: _ClassVar[Accuracy]

class Priority(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PRIORITY_DEFAULT: _ClassVar[Priority]
    PRIORITY_ROUTINE: _ClassVar[Priority]
    PRIORITY_NORMAL: _ClassVar[Priority]
    PRIORITY_URGENT: _ClassVar[Priority]
    PRIORITY_CRITICAL: _ClassVar[Priority]

class Consciousness(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CONSCIOUSNESS_UNKNOWN: _ClassVar[Consciousness]
    CONSCIOUSNESS_CONSCIOUS: _ClassVar[Consciousness]
    CONSCIOUSNESS_CONFUSED: _ClassVar[Consciousness]
    CONSCIOUSNESS_UNCONSCIOUS: _ClassVar[Consciousness]

class Breathing(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BREATHING_UNKNOWN: _ClassVar[Breathing]
    BREATHING_NORMAL: _ClassVar[Breathing]
    BREATHING_DIFFICULT: _ClassVar[Breathing]
    BREATHING_NONE: _ClassVar[Breathing]

class Mobility(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MOBILITY_UNKNOWN: _ClassVar[Mobility]
    MOBILITY_WALKS: _ClassVar[Mobility]
    MOBILITY_CANNOT_WALK: _ClassVar[Mobility]
    MOBILITY_TRAPPED: _ClassVar[Mobility]

class AgeGroup(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    AGE_UNKNOWN: _ClassVar[AgeGroup]
    AGE_CHILD: _ClassVar[AgeGroup]
    AGE_TEEN: _ClassVar[AgeGroup]
    AGE_ADULT: _ClassVar[AgeGroup]
    AGE_SENIOR: _ClassVar[AgeGroup]

class Injury(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    INJURY_NONE: _ClassVar[Injury]
    INJURY_HYPOTHERMIA: _ClassVar[Injury]
    INJURY_BLEEDING: _ClassVar[Injury]
    INJURY_FRACTURE: _ClassVar[Injury]
    INJURY_HEAD: _ClassVar[Injury]
    INJURY_SPINE: _ClassVar[Injury]
    INJURY_EXHAUSTION: _ClassVar[Injury]
    INJURY_OTHER: _ClassVar[Injury]
    INJURY_CARDIAC: _ClassVar[Injury]
    INJURY_HEAT: _ClassVar[Injury]
    INJURY_BURN: _ClassVar[Injury]
    INJURY_ALLERGY: _ClassVar[Injury]

class Need(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    NEED_NONE: _ClassVar[Need]
    NEED_STRETCHER: _ClassVar[Need]
    NEED_MEDIC: _ClassVar[Need]
    NEED_HELICOPTER: _ClassVar[Need]
    NEED_WARMTH: _ClassVar[Need]
    NEED_WATER: _ClassVar[Need]
    NEED_VEHICLE: _ClassVar[Need]
    NEED_OTHER: _ClassVar[Need]
    NEED_AED: _ClassVar[Need]
    NEED_OXYGEN: _ClassVar[Need]
    NEED_FIRST_AID: _ClassVar[Need]
    NEED_ROPE: _ClassVar[Need]
    NEED_LIGHT: _ClassVar[Need]
    NEED_FOOD: _ClassVar[Need]
    NEED_RADIO: _ClassVar[Need]

class SectorStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SECTOR_UNKNOWN: _ClassVar[SectorStatus]
    SECTOR_CLEARED: _ClassVar[SectorStatus]
    SECTOR_PARTIAL: _ClassVar[SectorStatus]
    SECTOR_BLOCKED: _ClassVar[SectorStatus]

class Resource(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RESOURCE_UNKNOWN: _ClassVar[Resource]
    RESOURCE_STRETCHER: _ClassVar[Resource]
    RESOURCE_MEDIC: _ClassVar[Resource]
    RESOURCE_HELICOPTER: _ClassVar[Resource]
    RESOURCE_VEHICLE: _ClassVar[Resource]
    RESOURCE_SEARCHERS: _ClassVar[Resource]
    RESOURCE_DOG_TEAM: _ClassVar[Resource]
    RESOURCE_DRONE: _ClassVar[Resource]
    RESOURCE_LIGHT: _ClassVar[Resource]
    RESOURCE_WATER: _ClassVar[Resource]
    RESOURCE_FOOD: _ClassVar[Resource]
    RESOURCE_WARMTH: _ClassVar[Resource]
    RESOURCE_BATTERIES: _ClassVar[Resource]
    RESOURCE_ROPE_TEAM: _ClassVar[Resource]
    RESOURCE_AMBULANCE: _ClassVar[Resource]
    RESOURCE_FIRE_BRIGADE: _ClassVar[Resource]
    RESOURCE_OTHER: _ClassVar[Resource]

class Urgency(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    URGENCY_UNKNOWN: _ClassVar[Urgency]
    URGENCY_NOW: _ClassVar[Urgency]
    URGENCY_SOON: _ClassVar[Urgency]
    URGENCY_LATER: _ClassVar[Urgency]

class HazardType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    HAZARD_UNKNOWN: _ClassVar[HazardType]
    HAZARD_BLOCKED_PATH: _ClassVar[HazardType]
    HAZARD_ROCKFALL: _ClassVar[HazardType]
    HAZARD_WATER: _ClassVar[HazardType]
    HAZARD_FIRE: _ClassVar[HazardType]
    HAZARD_CLIFF: _ClassVar[HazardType]
    HAZARD_ICE: _ClassVar[HazardType]
    HAZARD_ANIMAL: _ClassVar[HazardType]
    HAZARD_POWER_LINE: _ClassVar[HazardType]
    HAZARD_HUNTING: _ClassVar[HazardType]
    HAZARD_WEATHER: _ClassVar[HazardType]
    HAZARD_OTHER: _ClassVar[HazardType]

class Severity(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SEVERITY_UNKNOWN: _ClassVar[Severity]
    SEVERITY_LOW: _ClassVar[Severity]
    SEVERITY_MEDIUM: _ClassVar[Severity]
    SEVERITY_HIGH: _ClassVar[Severity]

class MaydayReason(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MAYDAY_UNKNOWN: _ClassVar[MaydayReason]
    MAYDAY_INJURED: _ClassVar[MaydayReason]
    MAYDAY_LOST: _ClassVar[MaydayReason]
    MAYDAY_MISSING_MEMBER: _ClassVar[MaydayReason]
    MAYDAY_MEDICAL: _ClassVar[MaydayReason]
    MAYDAY_TRAPPED: _ClassVar[MaydayReason]
    MAYDAY_THREAT: _ClassVar[MaydayReason]
    MAYDAY_OTHER: _ClassVar[MaydayReason]

class AckStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ACK_UNKNOWN: _ClassVar[AckStatus]
    ACK_ACCEPTED: _ClassVar[AckStatus]
    ACK_DECLINED: _ClassVar[AckStatus]
    ACK_DONE: _ClassVar[AckStatus]
ACCURACY_GPS: Accuracy
ACCURACY_GPS_POOR: Accuracy
ACCURACY_ESTIMATED: Accuracy
ACCURACY_AREA: Accuracy
PRIORITY_DEFAULT: Priority
PRIORITY_ROUTINE: Priority
PRIORITY_NORMAL: Priority
PRIORITY_URGENT: Priority
PRIORITY_CRITICAL: Priority
CONSCIOUSNESS_UNKNOWN: Consciousness
CONSCIOUSNESS_CONSCIOUS: Consciousness
CONSCIOUSNESS_CONFUSED: Consciousness
CONSCIOUSNESS_UNCONSCIOUS: Consciousness
BREATHING_UNKNOWN: Breathing
BREATHING_NORMAL: Breathing
BREATHING_DIFFICULT: Breathing
BREATHING_NONE: Breathing
MOBILITY_UNKNOWN: Mobility
MOBILITY_WALKS: Mobility
MOBILITY_CANNOT_WALK: Mobility
MOBILITY_TRAPPED: Mobility
AGE_UNKNOWN: AgeGroup
AGE_CHILD: AgeGroup
AGE_TEEN: AgeGroup
AGE_ADULT: AgeGroup
AGE_SENIOR: AgeGroup
INJURY_NONE: Injury
INJURY_HYPOTHERMIA: Injury
INJURY_BLEEDING: Injury
INJURY_FRACTURE: Injury
INJURY_HEAD: Injury
INJURY_SPINE: Injury
INJURY_EXHAUSTION: Injury
INJURY_OTHER: Injury
INJURY_CARDIAC: Injury
INJURY_HEAT: Injury
INJURY_BURN: Injury
INJURY_ALLERGY: Injury
NEED_NONE: Need
NEED_STRETCHER: Need
NEED_MEDIC: Need
NEED_HELICOPTER: Need
NEED_WARMTH: Need
NEED_WATER: Need
NEED_VEHICLE: Need
NEED_OTHER: Need
NEED_AED: Need
NEED_OXYGEN: Need
NEED_FIRST_AID: Need
NEED_ROPE: Need
NEED_LIGHT: Need
NEED_FOOD: Need
NEED_RADIO: Need
SECTOR_UNKNOWN: SectorStatus
SECTOR_CLEARED: SectorStatus
SECTOR_PARTIAL: SectorStatus
SECTOR_BLOCKED: SectorStatus
RESOURCE_UNKNOWN: Resource
RESOURCE_STRETCHER: Resource
RESOURCE_MEDIC: Resource
RESOURCE_HELICOPTER: Resource
RESOURCE_VEHICLE: Resource
RESOURCE_SEARCHERS: Resource
RESOURCE_DOG_TEAM: Resource
RESOURCE_DRONE: Resource
RESOURCE_LIGHT: Resource
RESOURCE_WATER: Resource
RESOURCE_FOOD: Resource
RESOURCE_WARMTH: Resource
RESOURCE_BATTERIES: Resource
RESOURCE_ROPE_TEAM: Resource
RESOURCE_AMBULANCE: Resource
RESOURCE_FIRE_BRIGADE: Resource
RESOURCE_OTHER: Resource
URGENCY_UNKNOWN: Urgency
URGENCY_NOW: Urgency
URGENCY_SOON: Urgency
URGENCY_LATER: Urgency
HAZARD_UNKNOWN: HazardType
HAZARD_BLOCKED_PATH: HazardType
HAZARD_ROCKFALL: HazardType
HAZARD_WATER: HazardType
HAZARD_FIRE: HazardType
HAZARD_CLIFF: HazardType
HAZARD_ICE: HazardType
HAZARD_ANIMAL: HazardType
HAZARD_POWER_LINE: HazardType
HAZARD_HUNTING: HazardType
HAZARD_WEATHER: HazardType
HAZARD_OTHER: HazardType
SEVERITY_UNKNOWN: Severity
SEVERITY_LOW: Severity
SEVERITY_MEDIUM: Severity
SEVERITY_HIGH: Severity
MAYDAY_UNKNOWN: MaydayReason
MAYDAY_INJURED: MaydayReason
MAYDAY_LOST: MaydayReason
MAYDAY_MISSING_MEMBER: MaydayReason
MAYDAY_MEDICAL: MaydayReason
MAYDAY_TRAPPED: MaydayReason
MAYDAY_THREAT: MaydayReason
MAYDAY_OTHER: MaydayReason
ACK_UNKNOWN: AckStatus
ACK_ACCEPTED: AckStatus
ACK_DECLINED: AckStatus
ACK_DONE: AckStatus

class Envelope(_message.Message):
    __slots__ = ()
    ID_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    POS_FIELD_NUMBER: _ClassVar[int]
    TIME_FIELD_NUMBER: _ClassVar[int]
    FOUND_FIELD_NUMBER: _ClassVar[int]
    TRACK_FIELD_NUMBER: _ClassVar[int]
    SECTOR_FIELD_NUMBER: _ClassVar[int]
    REQUEST_FIELD_NUMBER: _ClassVar[int]
    HAZARD_FIELD_NUMBER: _ClassVar[int]
    MAYDAY_FIELD_NUMBER: _ClassVar[int]
    ACK_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    id: int
    priority: Priority
    pos: Position
    time: int
    found: Found
    track: Track
    sector: Sector
    request: Request
    hazard: Hazard
    mayday: Mayday
    ack: Ack
    text: Text
    def __init__(self, id: _Optional[int] = ..., priority: _Optional[_Union[Priority, str]] = ..., pos: _Optional[_Union[Position, _Mapping]] = ..., time: _Optional[int] = ..., found: _Optional[_Union[Found, _Mapping]] = ..., track: _Optional[_Union[Track, _Mapping]] = ..., sector: _Optional[_Union[Sector, _Mapping]] = ..., request: _Optional[_Union[Request, _Mapping]] = ..., hazard: _Optional[_Union[Hazard, _Mapping]] = ..., mayday: _Optional[_Union[Mayday, _Mapping]] = ..., ack: _Optional[_Union[Ack, _Mapping]] = ..., text: _Optional[_Union[Text, _Mapping]] = ...) -> None: ...

class Position(_message.Message):
    __slots__ = ()
    LAT_FIELD_NUMBER: _ClassVar[int]
    LON_FIELD_NUMBER: _ClassVar[int]
    ACC_FIELD_NUMBER: _ClassVar[int]
    lat: int
    lon: int
    acc: Accuracy
    def __init__(self, lat: _Optional[int] = ..., lon: _Optional[int] = ..., acc: _Optional[_Union[Accuracy, str]] = ...) -> None: ...

class Found(_message.Message):
    __slots__ = ()
    STATE_FIELD_NUMBER: _ClassVar[int]
    INJURIES_FIELD_NUMBER: _ClassVar[int]
    NEEDS_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    RESPONDERS_FIELD_NUMBER: _ClassVar[int]
    state: int
    injuries: int
    needs: int
    count: int
    responders: int
    def __init__(self, state: _Optional[int] = ..., injuries: _Optional[int] = ..., needs: _Optional[int] = ..., count: _Optional[int] = ..., responders: _Optional[int] = ...) -> None: ...

class Track(_message.Message):
    __slots__ = ()
    LAT_FIELD_NUMBER: _ClassVar[int]
    LON_FIELD_NUMBER: _ClassVar[int]
    DT_FIELD_NUMBER: _ClassVar[int]
    lat: _containers.RepeatedScalarFieldContainer[int]
    lon: _containers.RepeatedScalarFieldContainer[int]
    dt: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, lat: _Optional[_Iterable[int]] = ..., lon: _Optional[_Iterable[int]] = ..., dt: _Optional[_Iterable[int]] = ...) -> None: ...

class Sector(_message.Message):
    __slots__ = ()
    SECTOR_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    COVERAGE_FIELD_NUMBER: _ClassVar[int]
    sector: int
    status: SectorStatus
    coverage: int
    def __init__(self, sector: _Optional[int] = ..., status: _Optional[_Union[SectorStatus, str]] = ..., coverage: _Optional[int] = ...) -> None: ...

class Request(_message.Message):
    __slots__ = ()
    RESOURCE_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    URGENCY_FIELD_NUMBER: _ClassVar[int]
    resource: Resource
    count: int
    urgency: Urgency
    def __init__(self, resource: _Optional[_Union[Resource, str]] = ..., count: _Optional[int] = ..., urgency: _Optional[_Union[Urgency, str]] = ...) -> None: ...

class Hazard(_message.Message):
    __slots__ = ()
    KIND_FIELD_NUMBER: _ClassVar[int]
    SEVERITY_FIELD_NUMBER: _ClassVar[int]
    RADIUS_FIELD_NUMBER: _ClassVar[int]
    kind: HazardType
    severity: Severity
    radius: int
    def __init__(self, kind: _Optional[_Union[HazardType, str]] = ..., severity: _Optional[_Union[Severity, str]] = ..., radius: _Optional[int] = ...) -> None: ...

class Mayday(_message.Message):
    __slots__ = ()
    REASON_FIELD_NUMBER: _ClassVar[int]
    PERSONS_FIELD_NUMBER: _ClassVar[int]
    reason: MaydayReason
    persons: int
    def __init__(self, reason: _Optional[_Union[MaydayReason, str]] = ..., persons: _Optional[int] = ...) -> None: ...

class Ack(_message.Message):
    __slots__ = ()
    REF_FIELD_NUMBER: _ClassVar[int]
    NODE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ETA_FIELD_NUMBER: _ClassVar[int]
    TEAM_FIELD_NUMBER: _ClassVar[int]
    ref: int
    node: int
    status: AckStatus
    eta: int
    team: str
    def __init__(self, ref: _Optional[int] = ..., node: _Optional[int] = ..., status: _Optional[_Union[AckStatus, str]] = ..., eta: _Optional[int] = ..., team: _Optional[str] = ...) -> None: ...

class Text(_message.Message):
    __slots__ = ()
    TEXT_FIELD_NUMBER: _ClassVar[int]
    text: str
    def __init__(self, text: _Optional[str] = ...) -> None: ...
