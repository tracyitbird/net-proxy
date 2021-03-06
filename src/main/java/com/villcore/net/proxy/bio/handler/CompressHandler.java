package com.villcore.net.proxy.bio.handler;

import com.villcore.net.proxy.bio.compressor.Compressor;
import com.villcore.net.proxy.bio.pkg2.Package;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class CompressHandler implements Handler {
    private static final Logger LOG = LoggerFactory.getLogger(CompressHandler.class);

    private Compressor compressor;

    public CompressHandler(Compressor compressor) {
        this.compressor = compressor;
    }

    @Override
    public Package handle(Package pkg) throws IOException {
        byte[] header = pkg.getHeader();
        byte[] body = pkg.getBody();

        byte[] compressHeader = compressor.compress(header);
        byte[] compressBody = compressor.compress(body);
        //LOG.debug("ori body size = {}, compress body size = {}", body.length, compressBody.length);

        Package newPkg = new Package();
        newPkg.setHeader(compressHeader);
        newPkg.setBody(compressBody);
        return newPkg;
    }
}
