# Williams Trader 4.12.0 — применение

Этот `FULL_SAFE` архив предназначен для **распаковки поверх существующей папки `~/Williams`**. Он не содержит `.env`, `data/`, `.git`, `.signing/` и `local.properties`, поэтому текущая конфигурация/БД не включаются в архив.

В Termux:

```bash
cd ~
tar -xzf ~/storage/downloads/Williams_4.12.0_FULL_SAFE.tar.gz
```

Архив создаёт/обновляет папку `~/Williams`. Не удаляйте существующую папку и не удаляйте `.env`/`data` перед распаковкой.

Альтернативный вариант — `Williams_4.12.0_Wave_MTF_PATCH.zip`. В нём есть `apply_williams_4.12.0.py`, который сам создаёт backup и запускает offline tests.
