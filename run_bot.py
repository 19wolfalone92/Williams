"""Direct CLI entrypoint for the authoritative Williams Futures runtime."""

import time

from dotenv import load_dotenv

from futures_williams_runtime import FuturesWilliamsRuntime


load_dotenv()


def main():
    runtime = FuturesWilliamsRuntime()
    runtime.recover()
    while True:
        runtime.process()
        time.sleep(runtime.poll_seconds)


if __name__ == "__main__":
    main()
