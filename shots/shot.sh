#!/bin/sh
# usage: shot.sh NAME "query" [height=1400] [budget=6000]   -> shots/NAME.png (500 px wide window)
H=${3:-1400}; B=${4:-6000}
/usr/bin/google-chrome --headless=new --no-sandbox --disable-gpu --hide-scrollbars --virtual-time-budget=$B --window-size=500,$H --screenshot=/home/claude/SwingEdge/shots/$1.png "file:///home/claude/SwingEdge/app/src/main/assets/ui/index.html?$2" >/dev/null 2>&1
