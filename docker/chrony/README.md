# Production time synchronisation

The VPS must use a host-level time synchronisation service (chrony/systemd-timesyncd).
The container must not attempt to own the host clock.

Recommended host configuration:
- enable chronyd/chrony;
- use at least two trusted NTP sources;
- monitor synchronization with chronyc tracking and chronyc sources;
- keep Binance timestamp correction in the application as a second safety layer.

Do not expose NTP from the trading container.
