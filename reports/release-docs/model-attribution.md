# Model and voice attribution

This document records provenance and the project's declared treatment. It is
not legal advice, a permission grant, or a clearance certificate.

## Voice and datasets

| Subject | Recorded attribution | License/status |
|---|---|---|
| Serbian Common Voice Style TTS Dataset | Created by Darko Milosevic; spoken by Dragana. Source: <https://huggingface.co/datasets/daremc86/serbian_common_voice> | The source record states CC BY 4.0; attribution and modification notice required. |
| JuzneVesti-SR v1.0 | Peter Rupnik and Nikola Ljubesic; Jozef Stefan Institute / CLARIN.SI. Source: <https://www.clarin.si/repository/xmlui/handle/11356/1679> | The project record declares CC BY-SA 4.0. DUA and underlying broadcast-rights review remain outstanding. |
| Derived Serbian Kokoro/Dragana package | Derived model, ONNX graph, voice/style data and test audio use the above provenance. | The project record treats the derived package as CC BY-SA 4.0, pending the outstanding review. |

Required notices identify both datasets, their authors/speaker or institutions,
source URLs, licenses, and the fact that the model/audio are modified or
derived. Generated audio reproduces characteristics of real people and must not
be used for impersonation or fraud.

## Legal gate

The model package legal status is **blocked** for public distribution. The
application and model package remain separate; the app does not bundle or
download model weights. Outstanding reviews recorded in the legal inventory:

- Obtain the exact CLARIN.SI DUA for handle 11356/1679.
- Obtain written advice on ShareAlike obligations for weights and underlying broadcast rights.

The declaration that a derived package may be treated as CC BY-SA 4.0 is a
project release treatment, not a verified legal conclusion. Do not publish a
model archive, voice archive, derived audio, or a cleared manifest based only on
this document. The package manifest's fail-closed legal object remains the
authoritative package gate.
