# Sources and licenses

Echo is a GPL-3.0 fork of Michał Rogalski's [mafik/echo](https://github.com/mafik/echo); original history and LICENSE.txt remain authoritative.

Adapted waveform: Artur Linnik, Toldya, commit `26e2729ebc0ed84ea0c67bce884938f5e0d7e824`, GPL-3.0. Compressed packet history is based on the design in shkarlsson/good-echo; disk rotation on mikenrafter/echo; exporter capacity handling on spidey000/saidIT. Exact source tips and decisions are in the integration ledger and source-refs.json. Code is adapted into the shared pipeline, not presented as unmodified source commits.

Dependencies:

- [Concentus 1.0.2](https://github.com/jaredmdobson/Concentus): portable Opus codec, BSD-3-Clause. Includes the Opus codec's upstream notices in its published artifact/source.
- [VorbisJava 0.8](https://github.com/Gagravarr/VorbisJava): Ogg framing and Opus headers, Apache-2.0. Its high-level audio-file buffering is not used.
- AndroidX and Material Components: Apache-2.0.

No speech model is bundled in the base build. Optional model additions must retain model-specific notices separately from the recognition library's license.
