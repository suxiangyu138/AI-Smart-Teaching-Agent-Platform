"""
数学PDF扫描页OCR识别服务
启动: pip install fastapi uvicorn paddlepaddle paddleocr pillow
      python ocr_server.py
接口: POST http://127.0.0.1:8001/ocr/page
"""
from fastapi import FastAPI, Request
from PIL import Image
import io
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ocr_server")

app = FastAPI(title="Math PDF OCR Service")

# 全局模型懒加载
_ocr = None
_latex_model = None


def get_ocr():
    global _ocr
    if _ocr is None:
        logger.info("Loading PaddleOCR...")
        from paddleocr import PaddleOCR
        _ocr = PaddleOCR(use_angle_cls=True, lang="ch", use_gpu=False)
        logger.info("PaddleOCR loaded")
    return _ocr


def get_latex_model():
    global _latex_model
    if _latex_model is None:
        try:
            logger.info("Loading Pix2Tex...")
            from pix2tex import cli as pix2tex_cli
            _latex_model = pix2tex_cli.LatexOCR()
            logger.info("Pix2Tex loaded")
        except ImportError:
            logger.warning("Pix2Tex not available, formula recognition disabled")
            _latex_model = None
    return _latex_model


@app.get("/health")
def health():
    return {"status": "ok", "ocr_loaded": _ocr is not None}


@app.post("/ocr/page")
async def ocr_page(request: Request):
    """识别单页扫描图片，返回带LaTeX的文本（接收原始PNG字节）"""
    try:
        img_bytes = await request.body()
        if not img_bytes:
            return {"page_content": "", "success": False, "error": "empty body"}
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")

        # 1. PaddleOCR 识别中文文字
        text_lines = []
        try:
            ocr = get_ocr()
            result = ocr.ocr(img, cls=True)
            if result and result[0]:
                for line in result[0]:
                    if line and len(line) > 1:
                        text_lines.append(line[1][0])
        except Exception as e:
            logger.error(f"OCR failed: {e}")
            text_lines.append("[OCR识别失败]")

        plain_text = "\n".join(text_lines) if text_lines else ""

        # 2. Pix2Tex 识别数学公式
        math_blocks = []
        latex_model = get_latex_model()
        if latex_model:
            try:
                latex_code = latex_model(img)
                if latex_code and latex_code.strip():
                    math_blocks.append(f"$$ {latex_code.strip()} $$")
            except Exception as e:
                logger.warning(f"Formula recognition skipped: {e}")

        full_content = plain_text
        if math_blocks:
            full_content += "\n" + "\n".join(math_blocks)

        return {"page_content": full_content, "success": True}

    except Exception as e:
        logger.error(f"Page processing error: {e}")
        return {"page_content": "", "success": False, "error": str(e)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("ocr_server:app", host="127.0.0.1", port=8001, log_level="info")
