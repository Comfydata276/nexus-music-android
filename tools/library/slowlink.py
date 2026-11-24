"""A slow link to the test server, for seeing a download part-way.

Listens on one port of this machine and passes everything to the test server on another,
sending what the server answers at most `kbps` kilobytes a second on each connection (what
the phone sends goes through as it is). With a rate of -1 it is a black hole instead: it
takes every connection and answers nothing, ever. scripts/slowlink points the emulator's own
"127.0.0.1:4533" at it. The emulator's network speed settings don't touch that tunnel, which
is adb's, so this is the only way to slow it.
"""
import asyncio
import sys
import time


async def pipe(reader, writer, rate):
    try:
        while True:
            started = time.monotonic()
            chunk = await reader.read(min(65536, rate // 10 or 65536) if rate else 65536)
            if not chunk:
                break
            writer.write(chunk)
            await writer.drain()
            if rate:
                # At most `rate` bytes a second: wait out what this chunk was worth.
                spare = len(chunk) / rate - (time.monotonic() - started)
                if spare > 0:
                    await asyncio.sleep(spare)
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def main(listen, target, kbps):
    rate = kbps * 1024

    async def swallow(client_reader, client_writer):
        # A black hole: the connection is taken, what is sent is read, and nothing ever
        # comes back. (Not a refusal, which a phone notices at once: this is a signal that is
        # there and carries nothing, and a request made into it hangs until it gives up.)
        try:
            while await client_reader.read(65536):
                pass
        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            try:
                client_writer.close()
            except Exception:
                pass

    async def handle(client_reader, client_writer):
        if kbps < 0:
            await swallow(client_reader, client_writer)
            return
        try:
            server_reader, server_writer = await asyncio.open_connection("127.0.0.1", target)
        except OSError:
            client_writer.close()
            return
        await asyncio.gather(pipe(client_reader, server_writer, 0), pipe(server_reader, client_writer, rate))

    server = await asyncio.start_server(handle, "127.0.0.1", listen)
    print(f"slow link: 127.0.0.1:{listen} -> " + ("nowhere (a black hole)" if kbps < 0 else f"127.0.0.1:{target} at {kbps} KB/s"), flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main(int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])))
