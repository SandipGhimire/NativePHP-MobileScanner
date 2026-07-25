<?php

namespace Sandip\Scanner\Native;

use InvalidArgumentException;

class PendingScan
{
    public const FORMATS = ['qr', 'ean13', 'ean8', 'code128', 'code39', 'upca', 'upce', 'all'];

    protected ?string $id = null;

    protected ?string $prompt = null;

    protected bool $continuous = false;

    protected array $formats = ['qr'];

    protected bool $started = false;

    public function prompt(string $prompt): self
    {
        $this->prompt = $prompt;

        return $this;
    }

    public function continuous(bool $continuous = true): self
    {
        $this->continuous = $continuous;

        return $this;
    }

    public function formats(array $formats): self
    {
        if ($formats === []) {
            throw new InvalidArgumentException('At least one barcode format must be specified.');
        }

        $invalid = array_diff($formats, self::FORMATS);

        if ($invalid !== []) {
            throw new InvalidArgumentException(sprintf(
                'Invalid barcode format(s): %s. Valid formats are: %s.',
                implode(', ', $invalid),
                implode(', ', self::FORMATS)
            ));
        }

        $this->formats = array_values(array_unique($formats));

        return $this;
    }

    public function id(string $id): self
    {
        $this->id = $id;

        return $this;
    }

    public function getId(): ?string
    {
        return $this->id;
    }

    public function scan(): bool
    {
        if ($this->started) {
            return false;
        }

        $this->started = true;

        if (! function_exists('nativephp_call')) {
            return false;
        }

        $result = nativephp_call('MobileScanner.Scan', json_encode([
            'prompt' => $this->prompt ?? 'Scan Code',
            'continuous' => $this->continuous,
            'formats' => $this->formats,
            'id' => $this->id,
        ]));

        if (! $result) {
            return false;
        }

        $decoded = json_decode($result, true);

        return ! (isset($decoded['status']) && $decoded['status'] === 'error');
    }

    public function __destruct()
    {
        if (! $this->started) {
            $this->scan();
        }
    }
}
