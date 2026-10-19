"""Configurable UTC admission policy; protection and exits never depend on it."""
from dataclasses import dataclass
from datetime import datetime, timezone
import re


def _minute(value):
    if not isinstance(value,str) or not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d',value):
        raise ValueError('Entry time must use HH:MM UTC')
    h,m=map(int,value.split(':'));return h*60+m


@dataclass(frozen=True)
class EntryTimePolicy:
    mode: str = 'ALWAYS'
    start_utc: str = '08:00'
    end_utc: str = '20:00'

    def __post_init__(self):
        if self.mode not in {'ALWAYS','UTC_WINDOW'}:raise ValueError('Unknown entry time policy')
        start,end=_minute(self.start_utc),_minute(self.end_utc)
        if self.mode=='UTC_WINDOW' and start==end:raise ValueError('Empty UTC entry window; choose ALWAYS for 24/7')

    def allows(self,epoch_ms):
        if not isinstance(epoch_ms,(int,float)) or epoch_ms<=0:return False
        try:now=datetime.fromtimestamp(epoch_ms/1000,timezone.utc)
        except (ValueError,OverflowError,OSError):return False
        if self.mode=='ALWAYS':return True
        minute=now.hour*60+now.minute;start,end=_minute(self.start_utc),_minute(self.end_utc)
        return start<=minute<end if start<end else minute>=start or minute<end

    def to_dict(self):return {'mode':self.mode,'start_utc':self.start_utc,'end_utc':self.end_utc,'timezone':'UTC','scope':'NEW_EXPOSURE_ONLY'}

    @classmethod
    def from_env(cls,env):return cls(env.get('ENTRY_TIME_MODE','ALWAYS'),env.get('ENTRY_TIME_START_UTC','08:00'),env.get('ENTRY_TIME_END_UTC','20:00'))
