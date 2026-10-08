"""Operational day-session policy; not part of Williams truth."""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime,time,timezone
@dataclass(frozen=True)
class IntradayPolicy:
    session_start_utc:str="08:00"; no_new_entries_utc:str="18:00"; flat_time_utc:str="20:00"; long_only:bool=True
    def _t(self,s):h,m=(int(x) for x in s.split(":")); return time(h,m,tzinfo=timezone.utc)
    def state(self,dt:datetime)->str:
        d=dt.astimezone(timezone.utc).time().replace(second=0,microsecond=0)
        if d<self._t(self.session_start_utc) or d>=self._t(self.flat_time_utc):return "CLOSED"
        if d>=self._t(self.no_new_entries_utc):return "NO_NEW_ENTRIES"
        return "OPEN"
    def allows_new_campaign(self,dt):return self.state(dt)=="OPEN"
    def must_flat(self,dt):return self.state(dt)=="CLOSED" and dt.astimezone(timezone.utc).time()>=self._t(self.flat_time_utc)
    def pending_action(self,dt):return "CANCEL" if dt.astimezone(timezone.utc).time()>=self._t(self.no_new_entries_utc) else "KEEP"
