# Third-party assets

## Inter

`app/src/main/res/font/inter_*.ttf` are subsets of Inter 4.1 by Rasmus Andersson,
licensed under the SIL Open Font License 1.1.

- Source: https://github.com/rsms/inter (release v4.1)
- Licence: https://github.com/rsms/inter/blob/master/LICENSE.txt

Subset to Latin-1 with the `tnum` (tabular figures) feature retained, which is what
keeps the running timer from jittering as the digits change. Regenerate with:

```
python3 -m fontTools.subset Inter-Regular.ttf \
  --unicodes="U+0000-00FF,U+0131,U+0152-0153,U+2000-206F,U+20AC,U+2212" \
  --layout-features="kern,liga,calt,tnum,ccmp,locl,mark,mkmk" \
  --output-file=inter_regular.ttf
```
