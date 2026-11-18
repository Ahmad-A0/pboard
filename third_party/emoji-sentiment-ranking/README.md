Emoji Sentiment Ranking 1.0
===========================

Petra Kralj Novak, Jasmina Smailović, Borut Sluban, Igor Mozetič (Jožef Stefan Institute), 2015.
Slovenian language resource repository CLARIN.SI, http://hdl.handle.net/11356/1048
Paper: "Sentiment of Emojis", PLoS ONE 10(12): e0144296, 2015.

Licence: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0),
https://creativecommons.org/licenses/by-sa/4.0/

Files (unmodified copies of the deposit, downloaded 2026-11-08):
- Emoji_Sentiment_Data_v1.0.csv  sha256 97465d4cf7e039fdca3f567310a4a9c225aa75e194945ccdb752df415ac6c201
- ESR_v1.0_format.txt            sha256 6867e275e92c84616439be50eb543715f64b6ba7521349a5d8f7fcb5aa1076b1

Not vendored: the deposit's Emojitracker_20150604.csv. Those counts were scraped from emojitracker.com, whose data
carries no licence of its own.

Used by tools/emoji_frequency.py, which keeps only the occurrence column and writes
app/src/main/assets/emoji/frequency.txt. That file is an adaptation and is CC BY-SA 4.0 too. It ranks the emoji in
the suggestion strip.
