#!/usr/bin/env bash
# Shared helpers for the Stockfish build scripts (sourced, not executed).
#
#   Stockfish release used by this app:  Stockfish 19  (official tag sf_19)
#   Official source:                     https://github.com/official-stockfish/Stockfish
#   Pinned commit (checked on every build): edb0d9db6731067ec50ce619ff372b463bc4dd5d
#
# SF_VERSION=11 selects the legacy Stockfish 11 tag.  It exists ONLY for the desktop test of the
# JNI bridge (see docs/ENGINE.md) and is never packaged into the APK.

ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SF_VERSION="${SF_VERSION:-19}"
case "$SF_VERSION" in
  19) SF_TAG=sf_19; SF_COMMIT=edb0d9db6731067ec50ce619ff372b463bc4dd5d ;;
  11) SF_TAG=sf_11; SF_COMMIT=c3483fa9a7d7c0ffa9fcc32b467ca844cfb63790 ;;
  *)  echo "unsupported SF_VERSION=$SF_VERSION (19, or 11 for bridge tests)"; exit 1 ;;
esac
SF_WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
SF_DIR="$SF_WORK/Stockfish-$SF_TAG"
SF_SRC="$SF_DIR/src"

sf_fetch_source() {
  mkdir -p "$SF_WORK"
  if [ ! -d "$SF_DIR/.git" ]; then
    echo "== fetching official Stockfish $SF_TAG =="
    git clone --quiet --depth 1 --branch "$SF_TAG" https://github.com/official-stockfish/Stockfish.git "$SF_DIR"
  fi
  local got
  got="$(git -C "$SF_DIR" rev-parse HEAD)"
  if [ "$got" != "$SF_COMMIT" ]; then
    echo "ERROR: $SF_TAG is at $got, expected $SF_COMMIT - refusing to build unverified sources"; exit 1
  fi
  echo "Stockfish source: $SF_TAG @ $got"
}

# Stockfish 19 embeds its NNUE network in the library.  The official net.sh downloads it and
# verifies that the file name (nn-<first 12 hex of sha256>.nnue) matches its SHA-256.
sf_fetch_net() {
  [ "$SF_VERSION" = "19" ] || return 0
  local name
  name="$(grep '#define EvalFileDefaultName' "$SF_SRC/evaluate.h" | sed 's/.*\(nn-[a-z0-9]\{12\}\.nnue\).*/\1/')"
  [ -n "$name" ] || { echo "ERROR: cannot read the network name"; exit 1; }
  if [ ! -f "$SF_SRC/$name" ]; then
    (cd "$SF_SRC" && sh ../scripts/net.sh) || true
  fi
  [ -f "$SF_SRC/$name" ] || { echo "ERROR: network $name could not be downloaded (needs access to tests.stockfishchess.org or github.com/official-stockfish/networks)"; exit 1; }
  local sum
  sum="$(sha256sum "$SF_SRC/$name" | cut -c1-12)"
  [ "nn-$sum.nnue" = "$name" ] || { echo "ERROR: $name does not match its SHA-256 ($sum)"; rm -f "$SF_SRC/$name"; exit 1; }
  SF_NET_NAME="$name"
  SF_NET_SHA256="$(sha256sum "$SF_SRC/$name" | cut -d' ' -f1)"
  echo "network: $name  sha256=$SF_NET_SHA256"
}

# source files that make up the engine (no main.cpp: the JNI bridge replaces it)
sf_sources() {
  if [ "$SF_VERSION" = "19" ]; then
    echo "attacks benchmark bitboard evaluate misc movegen movepick position search thread timeman tt uci ucioption tune syzygy/tbprobe nnue/nnue_accumulator nnue/nnue_misc nnue/network nnue/features/half_ka_v2_hm nnue/features/full_threats nnue/features/pp_3wide engine score memory"
  else
    echo "benchmark bitbase bitboard endgame evaluate material misc movegen movepick pawns position psqt search thread timeman tt uci ucioption syzygy/tbprobe"
  fi
}
