#!/usr/bin/env python3
"""Test opencli connectivity and Chrome login status on Linux."""
import subprocess, shutil, time, sys, json

NODE = shutil.which("node")
ENTRY = Path.home() / ".npm-global/lib/node_modules/@jackwener/opencli/dist/src/main.js"
from pathlib import Path

def run(*args, timeout=20):
    cmd = [NODE, str(ENTRY)] + list(args)
    print(f"  $ {' '.join(args)}")
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        out = r.stdout.strip()[:300]
        err = r.stderr.strip()[:300]
        if out:
            print(f"  -> {out}")
        if err:
            print(f"  !! {err}")
        return r.returncode, out, err
    except subprocess.TimeoutExpired:
        print(f"  !! TIMEOUT ({timeout}s)")
        return -1, "", "TIMEOUT"

print("=== Test 1: browser list ===")
run("browser", "list")

print("\n=== Test 2: init browser session ===")
run("browser", "xhs-dog-fetch", "init", "--headless=false", timeout=10)

print("\n=== Test 3: open xiaohongshu.com ===")
run("browser", "xhs-dog-fetch", "open", "https://www.xiaohongshu.com", timeout=30)

print("\n=== Test 4: eval test (check if logged in) ===")
run("browser", "xhs-dog-fetch", "eval", "document.title", timeout=30)
