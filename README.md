# MServer: a Simple MPlayer Web-Based Front End

## Usage

1. Put all your songs and music in some folder
2. You can organise them using sub-folders and symbolic links (for instance if you want per-interpret and per-composer folders, you may want to place a single piece in multiple locations).
3. You can put album art for a given item (media or folder) in a file with the same name but with `.jpeg` appended. For instance, art for `The Spring.ogg` should be placed in `The Spring.ogg.jpeg`, and art for a folder called `Vivaldi/` should be stored in `Vivaldi.jpeg`.
4. Run `mserver`, giving it as parameter the link to the folder, plus any parameter you want it to pass to `mplayer` (typically, to select audio output). For instance, I use the following command to run my instance:

```shell
mserver $HOME/music -ao alsa:noblock:device=hw=0.0
```

5. Open http://localhost:4568 in a browser
6. Click on the song you want played. Clicking multiple songs will queue them.

There's a currently undocumented REST API which is not exposed in the UI, to randomly play songs in a given folder until someone clicks STOP. Read the source to find out how to use it. I made a few themed folders like "relaxing music", "party music", etc, which contain symbolic links to relevant items to play randomly.
Then I hooked some Home Assistant automations to those.

## Bugs

* There's no way to know which song is currently playing (other than looking at the logs). This is planned.
* The progress bar sometimes goes too fast or too slowly. When it goes too fast it may go beyond the end. A fix is planned.
