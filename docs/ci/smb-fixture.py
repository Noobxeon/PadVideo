from pathlib import Path
from tempfile import TemporaryDirectory

from impacket import ntlm, smbserver


with TemporaryDirectory(prefix="padvideo-smb-") as directory:
    media = Path(directory)
    episodes = media / "剧集"
    episodes.mkdir()
    (episodes / "Episode 2 #.mp4").write_bytes(b"0123456789")
    (episodes / "Episode 10.mp4").write_bytes(b"abcdefghij")
    (episodes / "notes.txt").write_text("Ignored by the browser.", encoding="utf-8")

    server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=1445)
    server.setSMB2Support(True)
    server.addShare("VIDEOS", directory, readOnly="yes")
    server.addShare("家庭 影片", directory, readOnly="yes")
    server.addShare("HIDDEN$", directory, readOnly="yes")
    server.addCredential(
        "padvideo",
        0,
        ntlm.compute_lmhash("fixture-password"),
        ntlm.compute_nthash("fixture-password"),
    )
    server.start()
