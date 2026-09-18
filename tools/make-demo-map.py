#!/usr/bin/env python3
"""Draws the map the app ships with, over the ground the demo run covers.

The app has no INTERNET permission and never will, so a map has to arrive as a file.
For the demo run that file is in the APK, and it is drawn here rather than cut from
somebody else's tiles: a map we drew carries no licence, no attribution requirement and
no tile-server policy, and a demo run is synthetic anyway.

It is deliberately not a map of Paris. It is a plausible town over the same ground, so
the demo shows what a map *does* for a trace — streets to place it against, a river to
recognise, parks for contrast — without claiming to be somewhere real.

Deterministic: same seed, same tiles, so regenerating it produces no diff unless the
drawing changed.

    python3 tools/make-demo-map.py

Writes app/src/main/assets/demo.mbtiles.
"""

from __future__ import annotations

import math
import random
import sqlite3
import sys
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw

# The demo run's own start, from domain/demo/DemoRoute.kt. A kilometre around the loop
# is about 320 m across, so a 1 km box is room to pan well off the trace and still be on
# the map, without paying for ground nobody will ever look at.
CENTRE_LAT = 48.8566
CENTRE_LON = 2.3522
HALF_SPAN_M = 520.0

# 18 because that is roughly where a 320 m loop fills a phone screen: stopping at 17
# would mean the one view everybody sees first is an upscaled one.
MIN_ZOOM = 13
MAX_ZOOM = 18
TILE_SIZE = 256
SEED = 20260918

# Muted enough that a coral trace and a green one both read over it.
LAND = (242, 241, 236)
WATER = (199, 221, 234)
PARK = (219, 232, 212)
BUILDING = (223, 220, 212)
ROAD_FILL = (255, 255, 255)
ROAD_CASING = (222, 219, 210)
RAIL = (208, 205, 197)


@dataclass(frozen=True)
class Block:
    """Something built on, drawn only where there is room to tell it from its neighbour."""

    outline: list[tuple[float, float]]
    min_zoom: int
    max_zoom: int = 99


@dataclass(frozen=True)
class Road:
    """A street in local metres, east/north of the centre."""

    points: list[tuple[float, float]]
    width_m: float
    min_zoom: int


def metres_per_degree(lat: float) -> tuple[float, float]:
    """Local scale, matching domain/geo/GeoDistance.kt closely enough to draw with."""
    phi = math.radians(lat)
    per_lat = 111132.92 - 559.82 * math.cos(2 * phi) + 1.175 * math.cos(4 * phi)
    per_lon = 111412.84 * math.cos(phi) - 93.5 * math.cos(3 * phi)
    return per_lat, per_lon


PER_LAT, PER_LON = metres_per_degree(CENTRE_LAT)


def to_latlon(east: float, north: float) -> tuple[float, float]:
    return CENTRE_LAT + north / PER_LAT, CENTRE_LON + east / PER_LON


def world_xy(lat: float, lon: float, zoom: int) -> tuple[float, float]:
    """Web Mercator world pixels, the same projection the app draws tiles in."""
    size = TILE_SIZE * (2 ** zoom)
    x = (lon + 180.0) / 360.0 * size
    clamped = max(-85.05112878, min(85.05112878, lat))
    y = (1.0 - math.asinh(math.tan(math.radians(clamped))) / math.pi) / 2.0 * size
    return x, y


def rotate(east: float, north: float, degrees: float) -> tuple[float, float]:
    angle = math.radians(degrees)
    return (
        east * math.cos(angle) - north * math.sin(angle),
        east * math.sin(angle) + north * math.cos(angle),
    )


def build_world(rng: random.Random) -> tuple[list[Road], list[list[tuple[float, float]]], list, list]:
    """The town: streets, a river, parks and blocks, all in local metres."""
    reach = HALF_SPAN_M * 1.6

    roads: list[Road] = []

    # A grid, turned off true north. A grid square to the compass reads as a diagram;
    # eighteen degrees off reads as a town that grew along something.
    grid_angle = 18.0
    minor_spacing = 115.0
    major_every = 3

    steps = int(reach / minor_spacing) + 2
    for axis in (0, 1):
        for i in range(-steps, steps + 1):
            offset = i * minor_spacing + rng.uniform(-8.0, 8.0)
            major = i % major_every == 0
            ends = []
            for end in (-reach, reach):
                local = (offset, end) if axis == 0 else (end, offset)
                ends.append(rotate(local[0], local[1], grid_angle))
            roads.append(
                Road(
                    points=ends,
                    width_m=17.0 if major else 9.0,
                    min_zoom=14 if major else 16,
                )
            )

    # Two avenues cutting across it, which is what stops a grid looking generated.
    for angle in (-34.0, 62.0):
        ends = [rotate(-reach, 0.0, angle), rotate(reach, 0.0, angle)]
        roads.append(Road(points=[(e[0], e[1] + 40.0) for e in ends], width_m=24.0, min_zoom=13))

    # The river, a hand-wandered band across the south. Drawn from a few control points
    # so it bends the way water does rather than the way a sine wave does.
    river = [
        (-reach, -300.0),
        (-540.0, -260.0),
        (-230.0, -330.0),
        (60.0, -395.0),
        (380.0, -345.0),
        (690.0, -230.0),
        (reach, -190.0),
    ]

    parks = [
        [(-430.0, 120.0), (-200.0, 150.0), (-175.0, 340.0), (-415.0, 310.0)],
        [(230.0, 95.0), (470.0, 120.0), (485.0, 285.0), (215.0, 260.0)],
        [(-95.0, -205.0), (110.0, -197.0), (118.0, -100.0), (-88.0, -108.0)],
    ]

    # Blocks fill the space the grid leaves, every cell of it. A block that falls under
    # a major road is simply covered by it, because roads are drawn last — cheaper than
    # working out which cells the wide roads eat into.
    blocks = []
    for i in range(-steps, steps + 1):
        for j in range(-steps, steps + 1):
            east = i * minor_spacing + minor_spacing / 2
            north = j * minor_spacing + minor_spacing / 2
            if math.hypot(east, north) > reach * 0.8:
                continue
            half = minor_spacing / 2 - rng.uniform(14.0, 26.0)
            if half <= 6.0:
                continue

            # Split into buildings rather than filling the block. Close in, one grey
            # rectangle per block is the thing that makes a drawn map look drawn: real
            # ones have a courtyard and a gap between every second building.
            across = rng.randint(2, 3)
            down = rng.randint(2, 3)
            for bx in range(across):
                for by in range(down):
                    if rng.random() < 0.18:
                        continue  # a yard, a car park, a plot nobody built on
                    gap = 3.5
                    width = (2 * half) / across
                    height = (2 * half) / down
                    x0 = east - half + bx * width + gap
                    y0 = north - half + by * height + gap
                    x1 = x0 + width - 2 * gap
                    y1 = y0 + height - 2 * gap
                    corners = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
                    blocks.append(
                        Block(
                            outline=[rotate(x, y, grid_angle) for x, y in corners],
                            # Zoomed out, the gaps between buildings are sub-pixel and
                            # all they do is make the block look like noise. So the
                            # whole block is drawn as one shape until there is room.
                            min_zoom=17,
                        )
                    )
                    if bx == 0 and by == 0:
                        whole = [
                            (east - half, north - half),
                            (east + half, north - half),
                            (east + half, north + half),
                            (east - half, north + half),
                        ]
                        blocks.append(
                            Block(
                                outline=[rotate(x, y, grid_angle) for x, y in whole],
                                min_zoom=16,
                                max_zoom=16,
                            )
                        )

    return roads, parks, river, blocks


def draw_tile(zoom: int, tx: int, ty: int, world) -> bytes:
    roads, parks, river, blocks = world
    image = Image.new("RGB", (TILE_SIZE, TILE_SIZE), LAND)
    draw = ImageDraw.Draw(image)

    origin_x = tx * TILE_SIZE
    origin_y = ty * TILE_SIZE
    metres_per_pixel = (
        40075016.686 * math.cos(math.radians(CENTRE_LAT)) / (TILE_SIZE * 2 ** zoom)
    )

    def project(point: tuple[float, float]) -> tuple[float, float]:
        lat, lon = to_latlon(point[0], point[1])
        x, y = world_xy(lat, lon, zoom)
        return x - origin_x, y - origin_y

    def width_px(metres: float, floor: float) -> float:
        return max(floor, metres / metres_per_pixel)

    for block in blocks:
        if block.min_zoom <= zoom <= block.max_zoom:
            draw.polygon([project(p) for p in block.outline], fill=BUILDING)

    for park in parks:
        draw.polygon([project(p) for p in park], fill=PARK)

    river_px = [project(p) for p in river]
    draw.line(river_px, fill=WATER, width=int(width_px(110.0, 3.0)), joint="curve")

    # Casing first, then fill on top: two passes over every road, which is what gives
    # a junction a continuous outline instead of a seam at every crossing.
    visible = [r for r in roads if zoom >= r.min_zoom]
    for pass_colour, extra in ((ROAD_CASING, 2.4), (ROAD_FILL, 0.0)):
        for road in visible:
            points = [project(p) for p in road.points]
            draw.line(
                points,
                fill=pass_colour,
                width=int(width_px(road.width_m, 1.0) + extra),
                joint="curve",
            )

    buffer = BytesIO()
    # Palette mode, because the whole map is a dozen flat colours and a palette PNG of
    # one is a quarter the size of a truecolour one.
    image.convert("P", palette=Image.ADAPTIVE, colors=32).save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def tile_range(zoom: int) -> tuple[int, int, int, int]:
    south, west = to_latlon(-HALF_SPAN_M, -HALF_SPAN_M)
    north, east = to_latlon(HALF_SPAN_M, HALF_SPAN_M)
    min_x, max_y = world_xy(south, west, zoom)
    max_x, min_y = world_xy(north, east, zoom)
    return (
        int(min_x // TILE_SIZE),
        int(max_x // TILE_SIZE),
        int(min_y // TILE_SIZE),
        int(max_y // TILE_SIZE),
    )


def main() -> int:
    output = Path(__file__).resolve().parent.parent / "app/src/main/assets/demo.mbtiles"
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()

    world = build_world(random.Random(SEED))

    connection = sqlite3.connect(output)
    connection.executescript(
        """
        PRAGMA application_id = 1297105496;
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (
            zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB
        );
        CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
        """
    )

    south, west = to_latlon(-HALF_SPAN_M, -HALF_SPAN_M)
    north, east = to_latlon(HALF_SPAN_M, HALF_SPAN_M)
    connection.executemany(
        "INSERT INTO metadata VALUES (?, ?)",
        [
            ("name", "snail run demo zone"),
            ("format", "png"),
            ("type", "baselayer"),
            ("version", "1"),
            ("description", "Drawn for snail run's demo mode. Not a real place."),
            ("minzoom", str(MIN_ZOOM)),
            ("maxzoom", str(MAX_ZOOM)),
            ("bounds", f"{west:.6f},{south:.6f},{east:.6f},{north:.6f}"),
        ],
    )

    total = 0
    for zoom in range(MIN_ZOOM, MAX_ZOOM + 1):
        min_x, max_x, min_y, max_y = tile_range(zoom)
        for tx in range(min_x, max_x + 1):
            for ty in range(min_y, max_y + 1):
                data = draw_tile(zoom, tx, ty, world)
                # MBTiles counts rows from the bottom; the app flips them back.
                tms_row = (1 << zoom) - 1 - ty
                connection.execute(
                    "INSERT INTO tiles VALUES (?, ?, ?, ?)",
                    (zoom, tx, tms_row, sqlite3.Binary(data)),
                )
                total += 1
        print(f"z{zoom}: {(max_x - min_x + 1)}x{(max_y - min_y + 1)} tiles")

    connection.commit()
    connection.execute("VACUUM")
    connection.close()

    print(f"{total} tiles, {output.stat().st_size / 1024:.0f} KB -> {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
