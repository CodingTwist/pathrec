# pathrec

A mod to record the paths on Slabserver. For GamingTwist to then later analyze to and build the overlay for the [map.slabserver.org](https://map.slabserver.org) and [the wiki](https://wiki.slabserver.org/survival/season-4/map/). 

While recording, a HUD shows useful information, and your path is drawn in the world as a line. You can also mark points of interest, and undo the last ~10 m if you make a mistake. The output is a CSV. You will need to send to GamingTwist on Discord.

## Install

Grab the jar from [Releases](https://github.com/CodingTwist/pathrec/releases) and drop it in `.minecraft/mods` along with [Fabric API](https://modrinth.com/mod/fabric-api).

## Controls

| Key | Command | Does |
| --- | --- | --- |
| F6 | `/pathrec start [name]` / `/pathrec stop` | Start or stop recording |
| F7 | `/pathrec pause` | Pause or resume |
| F8 | `/pathrec undo` | Drop the last ~10 m |
| F9 | `/pathrec mark <text>` | Add a labelled mark (F9 numbers them) |

Keys can be rebound under Controls -> pathrec.

After an undo, recording holds until you walk back to the orange beam; the HUD points the way. Recording stops on its own if you disconnect.

## Output

Files go to `.minecraft/pathrec/`, named by start time (`20261117-142530.csv`). Click the filename in chat after stopping to open the folder.

The CSV gets a row each time you move 1 block horizontally:

```
ms,x,y,z,dim,block,ground_y
1763389530123,104.50,64.00,-220.31,minecraft:overworld,minecraft:grass_block,63
```


## Build

Needs JDK 25.

```
./gradlew build
```

The jar lands in `build/libs/`. Pushing a `v*` tag builds a GitHub Release:

```
git tag v0.2.0 && git push origin v0.2.0
```
